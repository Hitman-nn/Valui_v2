package com.valui.notify.vk;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VkNotificationDispatcher — unit tests")
class VkNotificationDispatcherTest {

    @Mock VkNotificationSender  vkSender;
    @Mock VkDeadLetterPublisher deadLetterPublisher;

    @InjectMocks VkNotificationDispatcher dispatcher;

    static final long   PEER_ID = 2000000001L;
    static final String LOG_ID  = UUID.randomUUID().toString();

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid request with vkPeerId → dispatch() called with deterministic randomId")
    void validRequest_dispatchCalled() {
        willDoNothing().given(vkSender).dispatch(anyLong(), anyString(), anyLong());

        dispatcher.onVkNotificationPending(record(request(PEER_ID, LOG_ID)));

        verify(vkSender).dispatch(eq(PEER_ID), eq("hello"), anyLong());
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    @DisplayName("same logId always produces same randomId (idempotency via VK random_id)")
    void sameLogId_sameRandomId() {
        long id1 = KafkaNotifyUtil_vkRandomId(LOG_ID);
        long id2 = KafkaNotifyUtil_vkRandomId(LOG_ID);

        org.assertj.core.api.Assertions.assertThat(id1).isEqualTo(id2).isNotZero();
    }

    // ── no vkPeerId ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("request without vkPeerId → skipped, no dispatch")
    void nullVkPeerId_skipped() {
        dispatcher.onVkNotificationPending(record(request(null, LOG_ID)));

        verifyNoInteractions(vkSender, deadLetterPublisher);
    }

    // ── unexpected payload ────────────────────────────────────────────────────

    @Test
    @DisplayName("unexpected payload type → skipped gracefully")
    void unexpectedPayload_skipped() {
        dispatcher.onVkNotificationPending(
                new ConsumerRecord<>("vk.notifications.pending", 0, 0L, "key", "not-a-message"));

        verifyNoInteractions(vkSender, deadLetterPublisher);
    }

    // ── error isolation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatch throws RetryableNotificationException → routes to DLQ")
    void dispatchThrows_routesToDlq() {
        willThrow(new RetryableNotificationException("VK rate limit", null, true, 0))
                .given(vkSender).dispatch(anyLong(), anyString(), anyLong());

        dispatcher.onVkNotificationPending(record(request(PEER_ID, LOG_ID)));

        verify(deadLetterPublisher).publishToDlq(any(), any());
    }

    @Test
    @DisplayName("dispatch throws unexpected RuntimeException → wrapped and routes to DLQ")
    void unexpectedException_wrappedAndRouted() {
        willThrow(new IllegalStateException("unexpected"))
                .given(vkSender).dispatch(anyLong(), anyString(), anyLong());

        dispatcher.onVkNotificationPending(record(request(PEER_ID, LOG_ID)));

        verify(deadLetterPublisher).publishToDlq(any(),
                argThat(e -> e.isRetryable() && e.getMessage().contains("unexpected")));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ConsumerRecord<String, Object> record(Object value) {
        return new ConsumerRecord<>("vk.notifications.pending", 0, 0L, "key", value);
    }

    private UserNotificationRequestMessage request(Long vkPeerId, String logId) {
        return new UserNotificationRequestMessage(
                logId,                        // notificationLogId
                UUID.randomUUID().toString(), // userId
                123456L,                      // telegramId
                "TELEGRAM",                   // channel
                "hello",                      // messageText
                UUID.randomUUID().toString(), // eventId
                null,                         // quickAddKey
                null,                         // eventUrl
                null,                         // betKey
                null,                         // dedupKey
                null,                         // editMessageId
                null,                         // bookmaker
                null,                         // dedupTtlMinutes
                vkPeerId,                     // vkPeerId
                null,                         // hasHcap
                null);                        // hasTotal
    }

    // Thin wrapper to test KafkaNotifyUtil without importing it directly
    private static long KafkaNotifyUtil_vkRandomId(String logId) {
        return com.valui.notify.util.KafkaNotifyUtil.vkRandomId(logId);
    }
}
