package com.valui.notify.config;

import com.valui.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.kafka.listener.ContainerProperties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Kafka consumer configuration for valui-notify.
 *
 * Two listener container factories:
 *
 *   kafkaListenerContainerFactory  (default)
 *     group:       valui-notify-group
 *     concurrency: 3  — matches partitions on user.notifications.pending (6 / 2)
 *     error handler: 2 retries with 1 s back-off, then DLQ
 *
 *   auditContainerFactory
 *     group:       valui-audit-group
 *     concurrency: 2  — audit consumers are lighter, 2 threads sufficient
 *     error handler: log and skip (audit loss is acceptable vs. poison-pill blocking)
 */
@Configuration
public class KafkaConsumerConfig {

    // ── Shared consumer factory ────────────────────────────────────────────────

    private ConsumerFactory<String, Object> consumerFactory(
            KafkaProperties kafkaProperties, String groupId) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        props.put(ConsumerConfig.GROUP_ID_CONFIG,             groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        // Trust all valui packages; type is resolved from __TypeId__ header written by producer
        props.put(JsonDeserializer.TRUSTED_PACKAGES,          "com.valui.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS,     true);
        // Disable auto-commit: we commit only after successful processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,   false);

        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(),
                new JsonDeserializer<>(Object.class, false));
    }

    // ── Default factory: notifications ────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, Object> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-notify-group"));
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(notifyErrorHandler(kafkaTemplate));
        return factory;
    }

    /**
     * On processing failure: retry twice with 1-second back-off, then publish to DLQ.
     * The recoverer routes to {original-topic}.DLQ by default, but we override the
     * destination to the single shared notifications.dlq topic.
     */
    private CommonErrorHandler notifyErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        KafkaTopics.NOTIFICATIONS_DLQ, record.partition() % 3));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
    }

    // ── Dispatch factory: user.notifications.pending ──────────────────────────

    /**
     * Factory for {@link com.valui.notify.dispatcher.NotificationDispatcher}.
     * concurrency=2 — notifications are I/O-bound (Telegram HTTP); 2 threads is enough.
     * Error handler routes to DLQ on persistent failure (after 2 retries).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dispatchContainerFactory(
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, Object> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-notify-dispatch-group"));
        factory.setConcurrency(2);
        factory.setCommonErrorHandler(notifyErrorHandler(kafkaTemplate));
        return factory;
    }

    // ── DLQ factory: notifications.dlq ────────────────────────────────────────

    /**
     * Factory for {@link com.valui.notify.consumer.DlqConsumer}.
     * concurrency=1 — DLQ is low-volume; single thread avoids ordering issues.
     * No DLQ error handler — the consumer handles all retries itself; on permanent
     * failure it marks the log row FAILED without re-routing to another topic.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> dlqContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-dlq-group"));
        factory.setConcurrency(1);
        // MANUAL ack: DlqConsumer acknowledges from the scheduler thread after 5-min delay
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    /** Single-thread scheduler for DLQ delayed retries. shutdownNow on context close. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService dlqRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dlq-retry");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Retry factory ─────────────────────────────────────────────────────────

    /**
     * Shared factory for the three delayed-retry topics (1 s, 5 s, 30 s).
     * concurrency=1 per topic — retry traffic is low-volume and ordering matters.
     * No container-level error handler; RetryTopicConsumer handles all failures
     * itself via DeadLetterPublisher to avoid double-counting retry attempts.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> retryContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-retry-group"));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    // ── Audit factory ──────────────────────────────────────────────────────────

    /**
     * Batch listener: drains up to 50 records per poll, persists via saveAll.
     * Manual ACK (AckMode.MANUAL_IMMEDIATE) so we commit only after successful saveAll.
     * Error handler logs and skips — audit loss is preferable to poison-pill blocking.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> auditContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(auditConsumerFactory(kafkaProperties));
        factory.setConcurrency(2);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 1)));
        return factory;
    }

    private ConsumerFactory<String, Object> auditConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG,             "valui-audit-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES,          "com.valui.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS,     true);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,   false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,     50);
        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(),
                new JsonDeserializer<>(Object.class, false));
    }
}
