package com.valui.notify.consumer;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.retry.NotificationRetryPolicy;
import com.valui.notify.util.KafkaNotifyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import org.slf4j.MDC;

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
            groupId          = "${valui.kafka.groups.dlq:valui-dlq-group}",
            containerFactory = "dlqContainerFactory"
    )
    public void handleDlq(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        dlqRetryScheduler.schedule(() -> doProcess(record, ack), delayMs, TimeUnit.MILLISECONDS);
    }

    private void doProcess(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof UserNotificationRequestMessage request)) {
                log.atWarn()
                   .addKeyValue("payloadType", record.value() != null ? record.value().getClass().getSimpleName() : "null")
                   .log("[DLQ] Unexpected payload type, skipping");
                return;
            }

            UUID logId = KafkaNotifyUtil.parseLogId(request.notificationLogId());
            try (var logCtx   = MDC.putCloseable("logId",      logId != null ? logId.toString() : "");
                 var topicCtx = MDC.putCloseable("kafkaTopic", record.topic());
                 var chatCtx  = MDC.putCloseable("chatId", String.valueOf(request.telegramId()));
                 var chanCtx  = MDC.putCloseable("channel", String.valueOf(request.channel()))) {
                if (logId != null && logService.isAlreadySent(logId)) {
                    log.debug("[DLQ] Already sent — skipping");
                    return;
                }
                try {
                    dispatchService.dispatch(request);
                    if (logId != null) logService.markSent(logId);
                    log.atInfo()
                       .addKeyValue("channel", request.channel())
                       .log("[DLQ] 5-min retry succeeded");
                } catch (Exception e) {
                    // DEBUG not ERROR: deadLetterPublisher.publishToDlq() below immediately logs
                    // this same terminal failure at ERROR with setCause(ex) (same exception,
                    // since RetryableNotificationException wraps it) — this used to print the
                    // full stack trace twice at ERROR level for one conceptual failure.
                    log.atDebug()
                       .addKeyValue("userId", request.userId())
                       .log("[DLQ] Final attempt failed: {}", e.getMessage());
                    if (logId != null) logService.markFailed(logId, e.getMessage());
                    RetryableNotificationException rne = retryPolicy.classify(e);
                    deadLetterPublisher.publishToDlq(record, rne);
                }
            }
        } finally {
            ack.acknowledge();
        }
    }

}
