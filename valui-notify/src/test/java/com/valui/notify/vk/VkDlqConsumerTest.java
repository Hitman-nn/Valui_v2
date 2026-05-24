package com.valui.notify.vk;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VkDlqConsumer — unit tests")
class VkDlqConsumerTest {

    @Mock VkNotificationSender     vkSender;
    @Mock VkDeadLetterPublisher    deadLetterPublisher;
    @Mock ScheduledExecutorService vkDlqRetryScheduler;
    @Mock Acknowledgment           ack;

    VkDlqConsumer consumer;

    static final long   PEER_ID = 2000000001L;
    static final String LOG_ID  = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        consumer = new VkDlqConsumer(vkSender, deadLetterPublisher, vkDlqRetryScheduler, 0L);

        // Run the scheduled task synchronously
        given(vkDlqRetryScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .willAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; });
    }

    // ── success ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatch succeeds → ack committed, no DLQ routing")
    void dispatchSucceeds() {
        willDoNothing().given(vkSender).dispatch(anyLong(), anyString(), anyLong());

        consumer.handleDlq(record(request(PEER_ID)), ack);

        verify(vkSender).dispatch(eq(PEER_ID), anyString(), anyLong());
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }

    // ── failure ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dispatch throws → routes to DLQ, ack committed")
    void dispatchFails_routesToDlq() {
        willThrow(new RetryableNotificationException("VK error", null, true, 0))
                .given(vkSender).dispatch(anyLong(), anyString(), anyLong());

        consumer.handleDlq(record(request(PEER_ID)), ack);

        verify(deadLetterPublisher).publishToDlq(any(), any());
        verify(ack).acknowledge();
    }

    // ── guard conditions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null vkPeerId → skipped, ack committed")
    void nullPeerId_skipped() {
        consumer.handleDlq(record(request(null)), ack);

        verifyNoInteractions(vkSender, deadLetterPublisher);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("unexpected payload type → skipped, ack committed")
    void unexpectedPayload_skipped() {
        consumer.handleDlq(record("not-a-message"), ack);

        verifyNoInteractions(vkSender, deadLetterPublisher);
        verify(ack).acknowledge();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ConsumerRecord<String, Object> record(Object value) {
        return new ConsumerRecord<>("vk.notifications.dlq", 0, 0L, "key", value);
    }

    private UserNotificationRequestMessage request(Long vkPeerId) {
        return new UserNotificationRequestMessage(
                LOG_ID, UUID.randomUUID().toString(), 123456L,
                "TELEGRAM", "test message", UUID.randomUUID().toString(),
                null, null, null, null, null, null, null, vkPeerId);
    }
}
