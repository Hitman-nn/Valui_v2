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
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 4th and final retry tier: 5-minute delay before the last dispatch attempt.
 *
 * The listener returns immediately — the Kafka poll thread is never blocked.
 * The actual retry runs on a dedicated scheduler thread after {@code delayMs}.
 * The offset is acknowledged (committed) only after the retry completes,
 * so a crash during the wait causes re-delivery on the next startup.
 *
 * If this attempt also fails, {@link DeadLetterPublisher} routes the record to
 * {@code notifications.dlq.final} (retryCount > MAX_RETRIES → terminal DLQ).
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
    private final ScheduledExecutorService dlqRetryScheduler;

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_DLQ,
            groupId          = "valui-dlq-group",
            containerFactory = "dlqContainerFactory"
    )
    public void handleDlq(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        dlqRetryScheduler.schedule(() -> doProcess(record, ack), delayMs, TimeUnit.MILLISECONDS);
    }

    private void doProcess(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof UserNotificationRequestMessage request)) {
                log.warn("[DLQ] Unexpected payload type {}, skipping",
                        record.value() != null ? record.value().getClass().getSimpleName() : "null");
                return;
            }

            UUID logId = parseLogId(request.notificationLogId());
            if (logId != null && logService.isAlreadySent(logId)) {
                log.debug("[DLQ] Already sent — skipping logId={}", logId);
                return;
            }
            try {
                dispatchService.dispatch(request);
                if (logId != null) logService.markSent(logId);
                log.info("[DLQ] 5-min retry succeeded [logId={} channel={}]", logId, request.channel());
            } catch (Exception e) {
                log.error("[DLQ] Final attempt failed [logId={} userId={}]: {}", logId, request.userId(), e.getMessage());
                if (logId != null) logService.markFailed(logId, e.getMessage());
                RetryableNotificationException rne = retryPolicy.classify(e);
                deadLetterPublisher.publishToDlq(record, rne);
            }
        } finally {
            ack.acknowledge();
        }
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
