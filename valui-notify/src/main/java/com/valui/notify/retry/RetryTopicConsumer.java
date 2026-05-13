package com.valui.notify.retry;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handles the three time-delayed retry tiers (1 s, 5 s, 30 s).
 *
 * Each listener sleeps for the configured delay on a virtual thread (cheap),
 * attempts dispatch once, then either marks the log SENT or forwards to the
 * next tier via {@link DeadLetterPublisher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryTopicConsumer {

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService logService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final NotificationRetryPolicy retryPolicy;

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_1S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle1s(ConsumerRecord<String, Object> record) {
        attempt(record, 1_000L);
    }

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_5S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle5s(ConsumerRecord<String, Object> record) {
        attempt(record, 5_000L);
    }

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_30S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle30s(ConsumerRecord<String, Object> record) {
        attempt(record, 30_000L);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void attempt(ConsumerRecord<String, Object> record, long delayMs) {
        sleepQuietly(delayMs);

        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("[RETRY] Unexpected payload on {}, skipping", record.topic());
            return;
        }

        UUID logId = parseLogId(request.notificationLogId());
        if (logId != null && logService.isAlreadySent(logId)) {
            log.debug("[RETRY] Already sent — skipping logId={}", logId);
            return;
        }
        try {
            dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId);
            log.info("[RETRY] Success on topic={} logId={}", record.topic(), logId);
        } catch (Exception e) {
            RetryableNotificationException rne = retryPolicy.classify(e);
            log.warn("[RETRY] Failed on topic={} logId={}: {}", record.topic(), logId, e.getMessage());
            deadLetterPublisher.publishToDlq(record, rne);
        }
    }

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
