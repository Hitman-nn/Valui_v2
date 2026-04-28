package com.valui.notify.consumer;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.retry.NotificationRetryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 4th and final retry tier: 5-minute delay before the last dispatch attempt.
 *
 * If this attempt also fails, {@link DeadLetterPublisher} routes the record to
 * {@code notifications.dlq.final} (retryCount > MAX_RETRIES → terminal DLQ).
 * The notification log is marked FAILED and the DlqMonitor counter increments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    /** Package-private to allow zero-delay override in unit tests. */
    long delayMs = 5 * 60 * 1_000L;

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService logService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final NotificationRetryPolicy retryPolicy;

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_DLQ,
            groupId          = "valui-dlq-group",
            containerFactory = "dlqContainerFactory"
    )
    public void handleDlq(ConsumerRecord<String, Object> record) {
        sleepQuietly(delayMs);

        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("[DLQ] Unexpected payload type {}, skipping",
                    record.value() != null ? record.value().getClass().getSimpleName() : "null");
            return;
        }

        UUID logId = parseLogId(request.notificationLogId());
        try {
            dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId);
            log.info("[DLQ] 5-min retry succeeded [logId={} channel={}]", logId, request.channel());
        } catch (Exception e) {
            log.error("[DLQ] Final attempt failed [logId={} userId={}]: {}", logId, request.userId(), e.getMessage());
            if (logId != null) logService.markFailed(logId, e.getMessage());
            RetryableNotificationException rne = retryPolicy.classify(e);
            deadLetterPublisher.publishToDlq(record, rne); // retryCount(4+1) > MAX → dlq.final
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
