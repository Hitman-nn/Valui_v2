package com.valui.notify.consumer;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.log.NotificationLogService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DlqConsumer — unit tests")
class DlqConsumerTest {

    @Mock NotificationDispatchService dispatchService;
    @Mock NotificationLogService      logService;
    @InjectMocks DlqConsumer          consumer;

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
                UUID.randomUUID().toString());
    }

    private ConsumerRecord<String, Object> record(Object value) {
        return new ConsumerRecord<>("notifications.dlq", 0, 0L, "key", value);
    }

    // ── success on first retry ────────────────────────────────────────────────

    @Test
    @DisplayName("first retry succeeds → markSent called, no markFailed")
    void firstRetrySucceeds_marksSent() throws Exception {
        // dispatch succeeds on first call
        willDoNothing().given(dispatchService).dispatch(request);

        consumer.handleDlq(record(request));

        verify(dispatchService, times(1)).dispatch(request);
        verify(logService).markSent(LOG_ID);
        verify(logService, never()).markFailed(any(), any());
    }

    // ── failure on all 3 retries ──────────────────────────────────────────────

    @Test
    @DisplayName("all 3 retries fail → markFailed called with error message")
    void allRetriesFail_marksFailed() throws Exception {
        willThrow(new RuntimeException("send error")).given(dispatchService).dispatch(request);

        consumer.handleDlq(record(request));

        verify(dispatchService, times(3)).dispatch(request);
        verify(logService, never()).markSent(any());
        verify(logService).markFailed(eq(LOG_ID), contains("send error"));
    }

    // ── success on third retry ────────────────────────────────────────────────

    @Test
    @DisplayName("first two retries fail, third succeeds → markSent called")
    void thirdRetrySucceeds_marksSent() throws Exception {
        willThrow(new RuntimeException("fail"))
                .willThrow(new RuntimeException("fail"))
                .willDoNothing()
                .given(dispatchService).dispatch(request);

        consumer.handleDlq(record(request));

        verify(dispatchService, times(3)).dispatch(request);
        verify(logService).markSent(LOG_ID);
        verify(logService, never()).markFailed(any(), any());
    }

    // ── unexpected payload type ───────────────────────────────────────────────

    @Test
    @DisplayName("unexpected payload type → skipped, no dispatch or log interaction")
    void unexpectedPayload_skipped() {
        consumer.handleDlq(record("not a notification"));

        verifyNoInteractions(dispatchService);
        verifyNoInteractions(logService);
    }
}
