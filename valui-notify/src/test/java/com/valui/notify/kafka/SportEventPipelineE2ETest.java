package com.valui.notify.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.consumer.SportEventConsumer;
import com.valui.notify.formatter.NotificationFormatter;
import com.valui.notify.log.NotificationLogService;
import com.valui.betting.cache.BetNotifCacheService;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.UserPortService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.service.GlobalFilterService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * E2E Kafka pipeline test: sport.events.detected → SportEventConsumer → user.notifications.pending.
 *
 * Uses @EmbeddedKafka (no Docker) and mocked port services so the test is fast and hermetic.
 * Verifies that a SportEventDetectedMessage published on the inbound topic causes
 * a UserNotificationRequestMessage to appear on the outbound topic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = SportEventPipelineE2ETest.Config.class
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
        "spring.kafka.consumer.group-id=e2e-test-group",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@DirtiesContext
class SportEventPipelineE2ETest {

    // ── Test Spring context ───────────────────────────────────────────────────

    @Configuration
    @EnableKafka
    @Import(SportEventConsumer.class)
    static class Config {

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        public KafkaTemplate<String, Object> kafkaTemplate(EmbeddedKafkaBroker broker) {
            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        }

        @Bean
        public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
                EmbeddedKafkaBroker broker) {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-consumer-group");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
            props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.valui.*");
            // true: use the __TypeId__ header written by the producer to pick the target class
            props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);

            // Don't pass an explicit deserializer — let Spring build it from the props map
            // so that USE_TYPE_INFO_HEADERS and TRUSTED_PACKAGES are respected.
            ConsumerFactory<String, Object> cf = new DefaultKafkaConsumerFactory<>(props);

            var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
            factory.setConsumerFactory(cf);
            return factory;
        }

        // ── Mocked port services ──────────────────────────────────────────────

        @Bean
        public ControllerPortService controllerPortService() {
            ControllerPortService svc = mock(ControllerPortService.class);
            ControllerEntity ctrl = ControllerEntity.builder()
                    .id(E2E_CTRL_ID)
                    .bookmaker(BookmakerType.FONBET)
                    .isActive(true).isMuted(false)
                    .type(ControllerType.TOURNAMENT)
                    .build();
            given(svc.findById(E2E_CTRL_ID)).willReturn(Optional.of(ctrl));
            return svc;
        }

        @Bean
        public UserPortService userPortService() {
            UserPortService svc = mock(UserPortService.class);
            UserEntity user = UserEntity.builder()
                    .id(E2E_USER_ID).telegramId(E2E_TG_ID).status(UserStatus.ACTIVE).build();
            given(svc.findById(E2E_USER_ID)).willReturn(Optional.of(user));
            return svc;
        }

        @Bean
        public DetectedEventPortService detectedEventPortService() {
            DetectedEventPortService svc = mock(DetectedEventPortService.class);
            given(svc.findIdByControllerIdAndExternalId(any(), any())).willReturn(Optional.empty());
            return svc;
        }

        @Bean
        public GlobalFilterService globalFilterService() {
            GlobalFilterService svc = mock(GlobalFilterService.class);
            given(svc.findByUserId(any())).willReturn(List.of());
            return svc;
        }

        @Bean
        public NotificationLogService notificationLogService() {
            NotificationLogService svc = mock(NotificationLogService.class);
            NotificationLogEntity log = NotificationLogEntity.builder()
                    .id(UUID.randomUUID()).status(NotificationStatus.PENDING).build();
            given(svc.createPending(any(), any(), any(), any())).willReturn(log);
            return svc;
        }

        @Bean
        public NotificationFormatter notificationFormatter() {
            NotificationFormatter svc = mock(NotificationFormatter.class);
            given(svc.buildTelegramMessage(any(), any())).willReturn("🔔 Test notification");
            return svc;
        }

        @Bean
        public QuickAddCacheService quickAddCacheService() {
            return mock(QuickAddCacheService.class);
        }

        @Bean
        public BetNotifCacheService betNotifCacheService() {
            return mock(BetNotifCacheService.class);
        }

        @Bean
        public com.valui.notify.dedup.TitleDedupCacheService titleDedupCacheService() {
            com.valui.notify.dedup.TitleDedupCacheService svc =
                    mock(com.valui.notify.dedup.TitleDedupCacheService.class);
            // Default: no dedup hit — all events go through normally
            given(svc.computeKey(anyLong(), any(), any(), any())).willReturn("e2e-dedup-key");
            given(svc.find(any())).willReturn(Optional.empty());
            return svc;
        }
    }

    // ── Shared test fixtures ──────────────────────────────────────────────────

    static final UUID E2E_CTRL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID E2E_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final long E2E_TG_ID   = 123456789L;

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker          embeddedKafka;

    Consumer<String, UserNotificationRequestMessage> pendingConsumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "e2e-pending-verifier", "false", embeddedKafka);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.valui.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, UserNotificationRequestMessage.class.getName());

        pendingConsumer = new DefaultKafkaConsumerFactory<String, UserNotificationRequestMessage>(
                props, new StringDeserializer(),
                new JsonDeserializer<>(UserNotificationRequestMessage.class, false)
        ).createConsumer();

        embeddedKafka.consumeFromAnEmbeddedTopic(pendingConsumer, KafkaTopics.USER_NOTIFICATIONS_PENDING);
        pendingConsumer.seekToEnd(pendingConsumer.assignment());
        pendingConsumer.assignment().forEach(tp -> pendingConsumer.position(tp));
    }

    @AfterEach
    void tearDown() {
        pendingConsumer.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sport.events.detected → SportEventConsumer → user.notifications.pending [E2E]")
    void fullPipeline_publishesNotificationRequest() {
        SportEventDetectedMessage inbound = new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                E2E_CTRL_ID.toString(),
                E2E_USER_ID.toString(),
                E2E_TG_ID,
                E2E_TG_ID,
                "FONBET",
                "ext-match-e2e",
                "Spartak - CSKA E2E",
                "https://fonbet.ru/e2e",
                Instant.now());

        kafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, inbound.eventId(), inbound).join();

        ConsumerRecord<String, UserNotificationRequestMessage> record = KafkaTestUtils.getSingleRecord(
                pendingConsumer, KafkaTopics.USER_NOTIFICATIONS_PENDING, Duration.ofSeconds(10));

        assertThat(record).isNotNull();
        UserNotificationRequestMessage msg = record.value();
        assertThat(msg.userId()).isEqualTo(E2E_USER_ID.toString());
        assertThat(msg.telegramId()).isEqualTo(E2E_TG_ID);
        assertThat(msg.channel()).isEqualTo(NotificationChannel.TELEGRAM.name());
        assertThat(msg.notificationLogId()).isNotNull();
    }

    @Test
    @DisplayName("event for unknown controller → dropped, nothing on user.notifications.pending")
    void unknownController_dropsEvent_noPendingMessage() {
        SportEventDetectedMessage inbound = new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), // unknown controllerId — mock returns empty
                E2E_USER_ID.toString(),
                E2E_TG_ID, E2E_TG_ID,
                "FONBET", "ext-unknown", "Unknown Match", "https://fonbet.ru/x", Instant.now());

        kafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, inbound.eventId(), inbound).join();

        var records = KafkaTestUtils.getRecords(pendingConsumer, Duration.ofSeconds(2));
        assertThat(records.count()).isZero();
    }
}
