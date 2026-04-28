package com.valui.notify.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Kafka pipeline in valui-notify.
 *
 * Uses @EmbeddedKafka (no Docker, no external broker) for fast, deterministic tests.
 * Each test uses seekToEnd() after consumer subscribe so it only sees messages
 * produced within that test — no cross-test contamination.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = KafkaIntegrationTest.KafkaTestConfig.class
)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                KafkaTopics.SPORT_EVENTS_DETECTED,
                KafkaTopics.USER_NOTIFICATIONS_PENDING,
                KafkaTopics.NOTIFICATIONS_DLQ
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=test-group"
})
@DirtiesContext
class KafkaIntegrationTest {

    @Configuration
    @Import(KafkaAutoConfiguration.class)
    static class KafkaTestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        public KafkaTemplate<String, Object> testKafkaTemplate(
                org.springframework.boot.autoconfigure.kafka.KafkaProperties props) {
            Map<String, Object> config = new HashMap<>(props.buildProducerProperties(null));
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        }
    }

    @Autowired
    private KafkaTemplate<String, Object> testKafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, SportEventDetectedMessage> consumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("test-group", "false", embeddedKafka);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.valui.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, SportEventDetectedMessage.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        consumer = new DefaultKafkaConsumerFactory<String, SportEventDetectedMessage>(
                props, new StringDeserializer(),
                new JsonDeserializer<>(SportEventDetectedMessage.class, false)
        ).createConsumer();

        // consumeFromAnEmbeddedTopic uses assign() (not subscribe()) → assignment is immediate.
        // seekToEnd() is LAZY: the actual end offset is resolved on the next poll().
        // If we produce a message between seekToEnd() and poll(), the poll resolves end
        // to AFTER our message and misses it ("No records found"). Calling position() on
        // each assigned partition forces eager resolution of the end offset RIGHT NOW,
        // before any messages are produced in the test body.
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, KafkaTopics.SPORT_EVENTS_DETECTED);
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(tp -> consumer.position(tp)); // eager resolution
    }

    @AfterEach
    void tearDownConsumer() {
        consumer.close();
    }

    // ── Test 1: topic existence ────────────────────────────────────────────────

    @Test
    void sportEventsDetectedTopic_shouldExistOnBroker() {
        assertThat(embeddedKafka.getTopics())
                .contains(KafkaTopics.SPORT_EVENTS_DETECTED);
    }

    @Test
    void allRequiredTopics_shouldExistOnBroker() {
        assertThat(embeddedKafka.getTopics()).containsExactlyInAnyOrder(
                KafkaTopics.SPORT_EVENTS_DETECTED,
                KafkaTopics.USER_NOTIFICATIONS_PENDING,
                KafkaTopics.NOTIFICATIONS_DLQ
        );
    }

    // ── Test 2: produce → consume ──────────────────────────────────────────────

    @Test
    void sportEventDetected_shouldBeProducedAndConsumed() {
        String eventId = UUID.randomUUID().toString();
        SportEventDetectedMessage message = new SportEventDetectedMessage(
                eventId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "FONBET",
                "FK Spartak - FK CSKA",
                "https://fonbet.ru/sports/football/1234",
                Instant.now()
        );

        // .join() ensures the message is committed to the broker before we start polling
        testKafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, message.eventId(), message).join();

        ConsumerRecord<String, SportEventDetectedMessage> record =
                KafkaTestUtils.getSingleRecord(consumer, KafkaTopics.SPORT_EVENTS_DETECTED,
                        Duration.ofSeconds(5));

        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo(eventId);
        assertThat(record.value().eventId()).isEqualTo(eventId);
        assertThat(record.value().bookmaker()).isEqualTo("FONBET");
        assertThat(record.value().title()).isEqualTo("FK Spartak - FK CSKA");
        assertThat(record.value().detectedAt()).isNotNull();
    }

    // ── Test 3: key partitioning ───────────────────────────────────────────────

    @Test
    void sportEventDetected_keyPartitioningIsStable() {
        // Same key must always land on the same partition (Kafka default hash partitioner).
        // We verify via SendResult metadata — no need to consume messages.
        String fixedKey = "stable-key-" + UUID.randomUUID();
        SportEventDetectedMessage msg1 = new SportEventDetectedMessage(
                fixedKey, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "OLIMP", "Match A", "https://olimp.bet/1", Instant.now());
        SportEventDetectedMessage msg2 = new SportEventDetectedMessage(
                fixedKey, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "OLIMP", "Match B", "https://olimp.bet/2", Instant.now());

        SendResult<String, Object> result1 =
                testKafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, fixedKey, msg1).join();
        SendResult<String, Object> result2 =
                testKafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, fixedKey, msg2).join();

        int partition1 = result1.getRecordMetadata().partition();
        int partition2 = result2.getRecordMetadata().partition();
        assertThat(partition1).isEqualTo(partition2);
    }

    // ── Test 4: DLQ topic writable ─────────────────────────────────────────────

    @Test
    void notificationsDlqTopic_shouldAcceptMessages() {
        SportEventDetectedMessage failed = new SportEventDetectedMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "BETBOOM",
                "Failed Match", "https://betboom.ru/0", Instant.now());

        Map<String, Object> dlqConsumerProps =
                KafkaTestUtils.consumerProps("dlq-test-group", "false", embeddedKafka);
        dlqConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        dlqConsumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, SportEventDetectedMessage.class.getName());
        dlqConsumerProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        dlqConsumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.valui.*");

        try (Consumer<String, SportEventDetectedMessage> dlqConsumer =
                     new DefaultKafkaConsumerFactory<String, SportEventDetectedMessage>(
                             dlqConsumerProps, new StringDeserializer(),
                             new JsonDeserializer<>(SportEventDetectedMessage.class, false)
                     ).createConsumer()) {

            embeddedKafka.consumeFromAnEmbeddedTopic(dlqConsumer, KafkaTopics.NOTIFICATIONS_DLQ);
            dlqConsumer.seekToEnd(dlqConsumer.assignment());
            dlqConsumer.assignment().forEach(tp -> dlqConsumer.position(tp)); // eager resolution

            testKafkaTemplate.send(KafkaTopics.NOTIFICATIONS_DLQ, failed.eventId(), failed).join();

            ConsumerRecord<String, SportEventDetectedMessage> record =
                    KafkaTestUtils.getSingleRecord(dlqConsumer, KafkaTopics.NOTIFICATIONS_DLQ,
                            Duration.ofSeconds(5));

            assertThat(record.value().bookmaker()).isEqualTo("BETBOOM");
        }
    }
}
