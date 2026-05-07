package com.valui.notify.consumer;

import com.valui.common.kafka.AuditEntryMessage;
import com.valui.user.api.AuditLogPortService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogConsumer — unit tests")
class AuditLogConsumerTest {

    @Mock AuditLogPortService auditLogPort;
    @Mock Acknowledgment      ack;

    @InjectMocks AuditLogConsumer consumer;

    @Test
    @DisplayName("Single message: delegated to AuditLogPortService and ACK sent")
    void singleMessage_persistedAndAcked() {
        AuditEntryMessage msg = new AuditEntryMessage(
                UUID.randomUUID().toString(), 42L, "ADD_CONTROLLER", "Controller",
                UUID.randomUUID().toString(), "{}", null, Instant.now());

        consumer.consume(List.of(msg), ack);

        verify(auditLogPort).persistBatch(eq(List.of(msg)));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Batch of 3 messages: all passed to persistBatch in one call")
    void batchMessages_allPersistedInOneSaveAll() {
        List<AuditEntryMessage> batch = List.of(
                makeMsg("ACT_1"),
                makeMsg("ACT_2"),
                makeMsg("ACT_3"));

        consumer.consume(batch, ack);

        verify(auditLogPort).persistBatch(eq(batch));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Empty batch: persistBatch not called, ACK still sent")
    void emptyBatch_noSave_ackSent() {
        consumer.consume(List.of(), ack);

        verifyNoInteractions(auditLogPort);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Message with null userId: still passed to persistBatch (mapping is port's concern)")
    void nullUserId_passedToPort() {
        AuditEntryMessage msg = new AuditEntryMessage(
                null, 55L, "BAN_USER", "User", null, "{}", null, Instant.now());

        consumer.consume(List.of(msg), ack);

        verify(auditLogPort).persistBatch(eq(List.of(msg)));
    }

    private AuditEntryMessage makeMsg(String action) {
        return new AuditEntryMessage(
                UUID.randomUUID().toString(), 42L, action, "Controller",
                UUID.randomUUID().toString(), "{}", null, Instant.now());
    }
}
