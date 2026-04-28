package com.valui.user.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.entity.AuditFallbackEntity;
import com.valui.common.event.AuditEvent;
import com.valui.common.kafka.KafkaTopics;
import com.valui.user.repository.AuditFallbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService — unit tests")
class AuditServiceFallbackTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock AuditFallbackRepository fallbackRepository;

    @InjectMocks AuditService auditService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var field = AuditService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(auditService, new ObjectMapper());
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kafka available: sends message and does NOT write to fallback")
    void kafkaAvailable_sendsMessage_noFallback() {
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(eq(KafkaTopics.AUDIT_LOG), anyString(), any())).willReturn(ok);

        auditService.log(buildEvent("ADD_CONTROLLER"));

        verify(kafkaTemplate).send(eq(KafkaTopics.AUDIT_LOG), anyString(), any());
        verifyNoInteractions(fallbackRepository);
    }

    @Test
    @DisplayName("Kafka send key equals userId string")
    void kafkaAvailable_keyIsUserId() {
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(eq(KafkaTopics.AUDIT_LOG), anyString(), any())).willReturn(ok);

        auditService.log(buildEvent("ADD_CONTROLLER"));

        verify(kafkaTemplate).send(eq(KafkaTopics.AUDIT_LOG), eq(userId.toString()), any());
    }

    // ── fallback ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kafka unavailable: writes AuditFallbackEntity to DB")
    void kafkaUnavailable_writesFallbackToDb() {
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(eq(KafkaTopics.AUDIT_LOG), anyString(), any())).willReturn(failed);

        auditService.log(buildEvent("REMOVE_CONTROLLER"));

        ArgumentCaptor<AuditFallbackEntity> captor = ArgumentCaptor.forClass(AuditFallbackEntity.class);
        verify(fallbackRepository).save(captor.capture());

        AuditFallbackEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAction()).isEqualTo("REMOVE_CONTROLLER");
    }

    @Test
    @DisplayName("Kafka unavailable and fallback fails: swallows exception, no rethrow")
    void kafkaAndFallbackFail_swallowsException() {
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(eq(KafkaTopics.AUDIT_LOG), anyString(), any())).willReturn(failed);
        given(fallbackRepository.save(any())).willThrow(new RuntimeException("db also down"));

        // Must not throw — audit failures are non-fatal
        auditService.log(buildEvent("BAN_USER"));
    }

    @Test
    @DisplayName("Null userId: Kafka key is 'anonymous'")
    void nullUserId_keyIsAnonymous() {
        CompletableFuture<SendResult<String, Object>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(eq(KafkaTopics.AUDIT_LOG), anyString(), any())).willReturn(ok);

        AuditEvent event = AuditEvent.builder()
                .action("ANON_ACTION")
                .occurredAt(Instant.now())
                .build();

        auditService.log(event);

        verify(kafkaTemplate).send(eq(KafkaTopics.AUDIT_LOG), eq("anonymous"), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuditEvent buildEvent(String action) {
        return AuditEvent.builder()
                .userId(userId)
                .telegramId(42L)
                .action(action)
                .entityType("Controller")
                .entityId(UUID.randomUUID())
                .details(Map.of("url", "https://example.com"))
                .occurredAt(Instant.now())
                .build();
    }
}
