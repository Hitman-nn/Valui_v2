package com.valui.notify.consumer;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.log.NotificationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dead-letter consumer: retries failed notifications with exponential back-off.
 *
 * Back-off schedule (applied BEFORE each attempt):
 *   Attempt 1 → wait 1 s
 *   Attempt 2 → wait 5 s
 *   Attempt 3 → wait 30 s
 *
 * After 3 consecutive failures the log row is marked FAILED and an admin alert
 * is emitted via log.error (replace with PagerDuty / Slack integration).
 *
 * Runs on virtual threads (configured in AsyncConfig); Thread.sleep is cheap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    private static final long[] BACKOFF_MS = {1_000L, 5_000L, 30_000L};

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService      logService;

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_DLQ,
            groupId          = "valui-dlq-group",
            containerFactory = "dlqContainerFactory"
    )
    public void handleDlq(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("DLQ: unexpected payload type {}, skipping",
                    record.value() != null ? record.value().getClass().getSimpleName() : "null");
            return;
        }

        UUID logId = parseLogId(request.notificationLogId());
        Exception lastException = null;

        for (int i = 0; i < BACKOFF_MS.length; i++) {
            try {
                Thread.sleep(BACKOFF_MS[i]);
                dispatchService.dispatch(request);
                if (logId != null) logService.markSent(logId);
                log.info("DLQ retry {} succeeded [logId={} channel={}]",
                        i + 1, logId, request.channel());
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("DLQ consumer interrupted for logId={}", logId);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("DLQ attempt {} failed [logId={} channel={}]: {}",
                        i + 1, logId, request.channel(), e.getMessage());
            }
        }

        // All retries exhausted
        String errMsg = lastException != null ? lastException.getMessage() : "DLQ retries exhausted";
        if (logId != null) logService.markFailed(logId, errMsg);
        log.error("ALERT: notification permanently failed after {} DLQ retries [logId={} userId={} channel={}]",
                BACKOFF_MS.length, logId, request.userId(), request.channel());
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
