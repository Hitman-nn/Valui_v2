package com.valui.notify.consumer;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.retry.NotificationRetryPolicy;
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
 * DlqConsumer schedules the retry on dlqRetryScheduler and returns immediately.
 * Tests mock the scheduler to run the task synchronously (zero delay).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DlqConsumer — unit tests")
class DlqConsumerTest {

    @Mock NotificationDispatchService  dispatchService;
    @Mock NotificationLogService       logService;
    @Mock DeadLetterPublisher          deadLetterPublisher;
    @Mock NotificationRetryPolicy      retryPolicy;
    @Mock ScheduledExecutorService     dlqRetryScheduler;
    @Mock Acknowledgment               ack;

    @InjectMocks DlqConsumer consumer;

    static final UUID LOG_ID = UUID.randomUUID();

    UserNotificationRequestMessage request;

    @BeforeEach
    void setUp() {
        consumer.delayMs = 0L;

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
        given(dlqRetryScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .willAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; });

        given(retryPolicy.classify(any()))
                .willReturn(new RetryableNotificationException("err", null, true, 0L));
    }

    private ConsumerRecord<String, Object> record(Object value) {
        return new ConsumerRecord<>("notifications.dlq", 0, 0L, "key", value);
    }

    // ── success ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dispatch succeeds → markSent, ack committed, no DLQ routing")
    void dispatchSucceeds_marksSent() throws Exception {
        given(dispatchService.dispatch(request)).willReturn(null);

        consumer.handleDlq(record(request), ack);

        verify(dispatchService).dispatch(request);
        verify(logService).markSent(LOG_ID);
        verify(logService, never()).markFailed(any(), any());
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Dispatch fails → markFailed + DeadLetterPublisher called, ack committed")
    void dispatchFails_marksFailedAndRoutes() throws Exception {
        willThrow(new RuntimeException("send error")).given(dispatchService).dispatch(request);

        consumer.handleDlq(record(request), ack);

        verify(dispatchService).dispatch(request);
        verify(logService, never()).markSent(any());
        verify(logService).markFailed(eq(LOG_ID), contains("send error"));
        verify(deadLetterPublisher).publishToDlq(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Unexpected payload type → skipped, ack still committed")
    void unexpectedPayload_skipped() {
        consumer.handleDlq(record("not a notification"), ack);

        verifyNoInteractions(dispatchService);
        verifyNoInteractions(logService);
        verifyNoInteractions(deadLetterPublisher);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Null payload → skipped gracefully, ack committed")
    void nullPayload_skipped() {
        consumer.handleDlq(record(null), ack);

        verifyNoInteractions(dispatchService);
        verify(ack).acknowledge();
    }
}
