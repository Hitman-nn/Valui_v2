package com.valui.notify.config;

import com.valui.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.kafka.listener.ContainerProperties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ThreadFactory;

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

    private static final String OFFSET_LATEST   = "latest";
    private static final String OFFSET_EARLIEST = "earliest";

    // Single source of truth — values declared in application.yml under spring.kafka.consumer.group-id
    // and related keys; prevents duplication between Java and YAML.
    @Value("${spring.kafka.consumer.group-id:valui-notify-group}")
    private String notifyGroupId;
    @Value("${valui.kafka.groups.dispatch:valui-notify-dispatch-group}")
    private String dispatchGroupId;
    @Value("${valui.kafka.groups.dlq:valui-dlq-group}")
    private String dlqGroupId;
    @Value("${valui.kafka.groups.retry:valui-retry-group}")
    private String retryGroupId;
    @Value("${valui.kafka.groups.audit:valui-audit-group}")
    private String auditGroupId;

    // ── Shared consumer factory ────────────────────────────────────────────────

    private Map<String, Object> baseProps(KafkaProperties kafkaProperties, String groupId, String autoOffsetReset) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG,             groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,    autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        // Trust all valui packages; type is resolved from __TypeId__ header written by producer
        props.put(JsonDeserializer.TRUSTED_PACKAGES,          "com.valui.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS,     true);
        // Disable auto-commit: we commit only after successful processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,   false);
        return props;
    }

    private ConsumerFactory<String, Object> consumerFactory(
            KafkaProperties kafkaProperties, String groupId, String autoOffsetReset) {
        return new DefaultKafkaConsumerFactory<>(baseProps(kafkaProperties, groupId, autoOffsetReset),
                new StringDeserializer(),
                new JsonDeserializer<>(Object.class, false));
    }

    // ── Default factory: notifications ────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, Object> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, notifyGroupId, OFFSET_LATEST));
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
        factory.setConsumerFactory(consumerFactory(kafkaProperties, dispatchGroupId, OFFSET_LATEST));
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
        factory.setConsumerFactory(consumerFactory(kafkaProperties, dlqGroupId, OFFSET_EARLIEST));
        factory.setConcurrency(1);
        // MANUAL ack: DlqConsumer acknowledges from the scheduler thread after 5-min delay
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    /** Single-thread scheduler for DLQ delayed retries. shutdownNow on context close. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService dlqRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(daemonThread("dlq-retry"));
    }

    // ── Retry factory ─────────────────────────────────────────────────────────

    /**
     * Shared factory for the three delayed-retry topics (1 s, 5 s, 30 s).
     * concurrency=1 — retry traffic is low-volume and ordering matters.
     * MANUAL ack: RetryTopicConsumer acknowledges from the scheduler thread after the delay.
     * No container-level error handler; RetryTopicConsumer handles all failures
     * itself via DeadLetterPublisher to avoid double-counting retry attempts.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> retryContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, retryGroupId, OFFSET_EARLIEST));
        factory.setConcurrency(1);
        // MANUAL ack: RetryTopicConsumer acknowledges from the scheduler thread after the delay
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    /** 3-thread scheduler for retry-topic delayed processing (one per retry tier). shutdownNow on context close. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService retryScheduler() {
        return Executors.newScheduledThreadPool(3, daemonThread("retry-scheduler"));
    }

    private static ThreadFactory daemonThread(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ── VK dispatch factory ───────────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> vkDispatchContainerFactory(
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, Object> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        // group.id is overridden by @KafkaListener.groupId in VkNotificationDispatcher
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-vk-dispatch-group", OFFSET_LATEST));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(notifyErrorHandler(kafkaTemplate));
        return factory;
    }

    // ── VK retry factory ──────────────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> vkRetryContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        // group.id is overridden by @KafkaListener.groupId in VkRetryTopicConsumer
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-vk-retry-group", OFFSET_EARLIEST));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    /** 3-thread scheduler for VK retry-topic delayed processing. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService vkRetryScheduler() {
        return Executors.newScheduledThreadPool(3, daemonThread("vk-retry-scheduler"));
    }

    // ── VK DLQ factory ────────────────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> vkDlqContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        // group.id is overridden by @KafkaListener.groupId in VkDlqConsumer
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-vk-dlq-group", OFFSET_EARLIEST));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0)));
        return factory;
    }

    /** Single-thread scheduler for VK DLQ delayed retries. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService vkDlqRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(daemonThread("vk-dlq-retry"));
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
        Map<String, Object> props = baseProps(kafkaProperties, auditGroupId, OFFSET_LATEST);
        // Batch size cap: drain up to 50 records per poll for saveAll efficiency
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(),
                new JsonDeserializer<>(Object.class, false));
    }
}
