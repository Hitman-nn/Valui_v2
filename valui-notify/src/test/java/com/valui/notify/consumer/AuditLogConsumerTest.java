package com.valui.notify.consumer;

import com.valui.common.entity.AuditLogEntity;
import com.valui.common.kafka.AuditEntryMessage;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogConsumer — unit tests")
class AuditLogConsumerTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock UserRepository userRepository;
    @Mock Acknowledgment ack;

    @InjectMocks AuditLogConsumer consumer;

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single message: persisted to audit_log and ACK sent")
    void singleMessage_persistedAndAcked() {
        UUID userId  = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        AuditEntryMessage msg = new AuditEntryMessage(
                userId.toString(), 42L, "ADD_CONTROLLER", "Controller",
                entityId.toString(), "{}", null, java.time.Instant.now());

        given(userRepository.getReferenceById(userId))
                .willReturn(stubUser(userId));

        consumer.consume(List.of(msg), ack);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLogEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());
        verify(ack).acknowledge();

        List<AuditLogEntity> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getAction()).isEqualTo("ADD_CONTROLLER");
        assertThat(saved.get(0).getEntityId()).isEqualTo(entityId);
    }

    @Test
    @DisplayName("Batch of 3 messages: all persisted in one saveAll call")
    void batchMessages_allPersistedInOneSaveAll() {
        List<AuditEntryMessage> batch = List.of(
                makeMsg(UUID.randomUUID(), "ACT_1"),
                makeMsg(UUID.randomUUID(), "ACT_2"),
                makeMsg(UUID.randomUUID(), "ACT_3")
        );
        batch.forEach(m -> given(userRepository.getReferenceById(UUID.fromString(m.userId())))
                .willReturn(stubUser(UUID.fromString(m.userId()))));

        consumer.consume(batch, ack);

        verify(auditLogRepository, times(1)).saveAll(argThat(l -> ((List<?>) l).size() == 3));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Empty batch: nothing persisted, ACK still sent")
    void emptyBatch_noSave_ackSent() {
        consumer.consume(List.of(), ack);

        verifyNoInteractions(auditLogRepository);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Message with null userId: persisted with null user reference")
    void nullUserId_persistedWithNullUser() {
        AuditEntryMessage msg = new AuditEntryMessage(
                null, 55L, "BAN_USER", "User", null, "{}", null, Instant.now());

        consumer.consume(List.of(msg), ack);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLogEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).saveAll(captor.capture());

        assertThat(captor.getValue().get(0).getUser()).isNull();
        verifyNoInteractions(userRepository); // should not call getReferenceById for null
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuditEntryMessage makeMsg(UUID userId, String action) {
        return new AuditEntryMessage(
                userId.toString(), 42L, action, "Controller",
                UUID.randomUUID().toString(), "{}", null, java.time.Instant.now());
    }

    private com.valui.common.entity.UserEntity stubUser(UUID id) {
        com.valui.common.entity.UserEntity u = new com.valui.common.entity.UserEntity();
        u.setId(id);
        return u;
    }
}
