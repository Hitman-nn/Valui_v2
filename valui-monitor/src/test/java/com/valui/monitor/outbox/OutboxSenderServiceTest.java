package com.valui.monitor.outbox;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.monitor.kafka.KafkaProducerMetrics;
import com.valui.monitor.kafka.SportEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxSenderService — unit tests")
class OutboxSenderServiceTest {

    @Mock OutboxEventRepository outboxRepo;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock SportEventMapper mapper;
    @Mock KafkaProducerMetrics metrics;
    @Mock OutboxMarkingService markingService;

    @InjectMocks OutboxSenderService service;

    private OutboxEvent outbox;
    private SportEventDetectedMessage message;

    @BeforeEach
    void setUp() {
        outbox = OutboxEvent.builder()
                .id(1L)
                .topic(KafkaTopics.SPORT_EVENTS_DETECTED)
                .messageKey(UUID.randomUUID().toString())
                .externalEventId("ext-1")
                .controllerId(UUID.randomUUID().toString())
                .userId(UUID.randomUUID().toString())
                .telegramId(42L)
                .bookmaker("FONBET")
                .title("Match A")
                .url("https://fonbet.ru/1")
                .createdAt(OffsetDateTime.now().minusMinutes(1))
                .build();

        message = new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                outbox.getControllerId(), outbox.getUserId(),
                outbox.getTelegramId(), outbox.getBookmaker(),
                outbox.getExternalEventId(), outbox.getTitle(), outbox.getUrl(),
                Instant.now());
    }

    // ── publishImmediate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("publishImmediate: found unsent row → sends to Kafka and marks sent on success")
    void publishImmediate_found_sendsAndMarks() {
        given(outboxRepo.findByExternalEventIdAndSentAtIsNull("ext-1")).willReturn(Optional.of(outbox));
        given(mapper.fromOutbox(outbox)).willReturn(message);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);

        service.publishImmediate("ext-1");

        verify(kafkaTemplate).send(any(ProducerRecord.class));
        // whenComplete fires synchronously for already-completed futures
        verify(markingService).markSent(1L);
    }

    @Test
    @DisplayName("publishImmediate: no unsent row → no Kafka send")
    void publishImmediate_notFound_noSend() {
        given(outboxRepo.findByExternalEventIdAndSentAtIsNull("ext-1")).willReturn(Optional.empty());

        service.publishImmediate("ext-1");

        verifyNoInteractions(kafkaTemplate);
    }

    // ── scanAndSend ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("scanAndSend: unsent events older than cutoff → all sent")
    void scanAndSend_unsentOldEvents_allSent() {
        given(outboxRepo.findUnsentBefore(any())).willReturn(List.of(outbox));
        given(mapper.fromOutbox(outbox)).willReturn(message);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));

        service.scanAndSend();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
        verify(markingService).markSent(1L);
    }

    @Test
    @DisplayName("scanAndSend: no unsent events → no Kafka interaction")
    void scanAndSend_noUnsent_noKafka() {
        given(outboxRepo.findUnsentBefore(any())).willReturn(List.of());

        service.scanAndSend();

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("scanAndSend: Kafka send failure → metrics recorded, row stays unsent")
    void scanAndSend_kafkaFailure_metricsAndNoMark() {
        given(outboxRepo.findUnsentBefore(any())).willReturn(List.of(outbox));
        given(mapper.fromOutbox(outbox)).willReturn(message);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(failed);

        service.scanAndSend();

        verify(metrics).onSendFailed();
        verifyNoInteractions(markingService);
    }

    // ── buildOutboxEvent ──────────────────────────────────────────────────────

    @Test
    @DisplayName("buildOutboxEvent: maps all fields correctly")
    void buildOutboxEvent_mapsAllFields() {
        String extId = "ext-match-99";
        String ctrlId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();

        OutboxEvent built = service.buildOutboxEvent(
                extId, ctrlId, userId, 55L, "OLIMP", "A - B", "https://olimp.bet/1");

        assertThat(built.getTopic()).isEqualTo(KafkaTopics.SPORT_EVENTS_DETECTED);
        assertThat(built.getMessageKey()).isEqualTo(ctrlId);
        assertThat(built.getExternalEventId()).isEqualTo(extId);
        assertThat(built.getControllerId()).isEqualTo(ctrlId);
        assertThat(built.getUserId()).isEqualTo(userId);
        assertThat(built.getTelegramId()).isEqualTo(55L);
        assertThat(built.getBookmaker()).isEqualTo("OLIMP");
        assertThat(built.getTitle()).isEqualTo("A - B");
        assertThat(built.getSentAt()).isNull();
        assertThat(built.getRetryCount()).isZero();
    }
}
