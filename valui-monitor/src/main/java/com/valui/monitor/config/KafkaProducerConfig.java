package com.valui.monitor.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Explicit Kafka producer configuration for valui-monitor.
 * Overrides Spring Boot auto-config to ensure idempotent delivery guarantees
 * regardless of what is set (or forgotten) in application.yml.
 *
 * Reliability contract:
 *   acks=all           — wait for all in-sync replicas before ack
 *   retries=3          — automatic retries on transient failures
 *   idempotence=true   — exactly-once semantics at broker level (requires acks=all)
 *   max-in-flight=5    — max allowed while preserving order with idempotence
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));

        // Enforce reliability settings regardless of yml overrides
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG,                   "all");
        props.put(ProducerConfig.RETRIES_CONFIG,                3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,     true);
        // idempotence requires max-in-flight <= 5; default is 5, explicit for clarity
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Batch tuning: wait up to 5 ms to fill a batch — reduces small-message overhead
        props.put(ProducerConfig.LINGER_MS_CONFIG,  5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        // Type headers allow the consumer's JsonDeserializer to resolve the target class
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props);
        factory.setValueSerializer(new JsonSerializer<>());
        return factory;
    }

    /**
     * Single generic KafkaTemplate used by all producers in the monolith.
     * Type-safe at the send() call site; generics are erased at runtime.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
