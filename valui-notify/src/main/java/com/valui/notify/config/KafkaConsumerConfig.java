package com.valui.notify.config;

import com.valui.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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

    // ── Audit factory ──────────────────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> auditContainerFactory(
            KafkaProperties kafkaProperties) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory(kafkaProperties, "valui-audit-group"));
        factory.setConcurrency(2);
        // Audit: log and skip on failure — audit loss is preferable to DLQ feedback loops
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 1)));
        return factory;
    }
}
