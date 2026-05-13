package com.valui.notify.retry;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
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

/**
 * RetryTopicConsumer schedules the retry on retryScheduler and returns immediately.
 * Tests mock the scheduler to run the task synchronously (zero delay).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetryTopicConsumer — unit tests")
class RetryTopicConsumerTest {

    @Mock NotificationDispatchService  dispatchService;
    @Mock NotificationLogService       logService;
    @Mock DeadLetterPublisher          deadLetterPublisher;
    @Mock NotificationRetryPolicy      retryPolicy;
    @Mock ScheduledExecutorService     retryScheduler;
    @Mock Acknowledgment               ack;

    @InjectMocks RetryTopicConsumer consumer;

    static final UUID LOG_ID = UUID.randomUUID();

    UserNotificationRequestMessage request;

    @BeforeEach
    void setUp() {
        request = new UserNotificationRequestMessage(
                LOG_ID.toString(),
                UUID.randomUUID().toString(),
                123456L,
                "TELEGRAM",
                "test message",
                UUID.randomUUID().toString(),
                null,
                null,
                null);

        // Run the scheduled task synchronously so tests remain deterministic
        given(retryScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .willAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; });

        given(retryPolicy.classify(any()))
                .willReturn(new RetryableNotificationException("err", null, true, 0L));
    }

    private ConsumerRecord<String, Object> record(String topic, Object value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    // ── handle1s ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1s tier: dispatch succeeds → markSent, ack committed, no DLQ routing")
    void handle1s_dispatchSucceeds_marksSent() throws Exception {
        given(dispatchService.dispatch(request)).willReturn(null);

        consumer.handle1s(record("notifications.retry.1s", request), ack);

        verify(retryScheduler).schedule(any(Runnable.class), eq(1_000L), eq(TimeUnit.MILLISECONDS));
        verify(dispatchService).dispatch(request);
        verify(logService).markSent(LOG_ID);
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("1s tier: dispatch fails → DeadLetterPublisher called, ack committed")
    void handle1s_dispatchFails_routesToDlq() throws Exception {
        willThrow(new RuntimeException("send error")).given(dispatchService).dispatch(request);

        consumer.handle1s(record("notifications.retry.1s", request), ack);

        verify(dispatchService).dispatch(request);
        verify(logService, never()).markSent(any());
        verify(deadLetterPublisher).publishToDlq(any(), any());
        verify(ack).acknowledge();
    }

    // ── handle5s ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5s tier: scheduled with correct 5000ms delay")
    void handle5s_scheduledWithCorrectDelay() throws Exception {
        given(dispatchService.dispatch(request)).willReturn(null);

        consumer.handle5s(record("notifications.retry.5s", request), ack);

        verify(retryScheduler).schedule(any(Runnable.class), eq(5_000L), eq(TimeUnit.MILLISECONDS));
        verify(ack).acknowledge();
    }

    // ── handle30s ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("30s tier: scheduled with correct 30000ms delay")
    void handle30s_scheduledWithCorrectDelay() throws Exception {
        given(dispatchService.dispatch(request)).willReturn(null);

        consumer.handle30s(record("notifications.retry.30s", request), ack);

        verify(retryScheduler).schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        verify(ack).acknowledge();
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unexpected payload type → skipped, ack still committed")
    void unexpectedPayload_skipped() {
        consumer.handle1s(record("notifications.retry.1s", "not a notification"), ack);

        verifyNoInteractions(dispatchService);
        verifyNoInteractions(logService);
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Null payload → skipped gracefully, ack committed")
    void nullPayload_skipped() {
        consumer.handle1s(record("notifications.retry.1s", null), ack);

        verifyNoInteractions(dispatchService);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Already sent → skipped, ack committed, no dispatch")
    void alreadySent_skipped() {
        given(logService.isAlreadySent(LOG_ID)).willReturn(true);

        consumer.handle1s(record("notifications.retry.1s", request), ack);

        verifyNoInteractions(dispatchService);
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }
}
