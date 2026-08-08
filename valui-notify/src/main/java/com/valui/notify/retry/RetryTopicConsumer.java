package com.valui.notify.retry;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dispatcher.NotificationDispatchService;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
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
 * Handles the three time-delayed retry tiers (1 s, 5 s, 30 s).
 *
 * Each listener returns immediately — the Kafka poll thread is never blocked.
 * The actual retry runs on a dedicated scheduler thread after the configured delay.
 * The offset is acknowledged only after the retry completes, so a crash during
 * the wait causes re-delivery on the next startup.
 *
 * On failure the record is forwarded to the next tier via {@link DeadLetterPublisher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryTopicConsumer {

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService logService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final NotificationRetryPolicy retryPolicy;
    private final ScheduledExecutorService retryScheduler;

    private static final long DELAY_1S  =  1_000L;
    private static final long DELAY_5S  =  5_000L;
    private static final long DELAY_30S = 30_000L;

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_1S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle1s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        retryScheduler.schedule(() -> doProcess(record, ack), DELAY_1S, TimeUnit.MILLISECONDS);
    }

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_5S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle5s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        retryScheduler.schedule(() -> doProcess(record, ack), DELAY_5S, TimeUnit.MILLISECONDS);
    }

    @KafkaListener(
            topics           = KafkaTopics.NOTIFICATIONS_RETRY_30S,
            groupId          = "valui-retry-group",
            containerFactory = "retryContainerFactory"
    )
    public void handle30s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        retryScheduler.schedule(() -> doProcess(record, ack), DELAY_30S, TimeUnit.MILLISECONDS);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void doProcess(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof UserNotificationRequestMessage request)) {
                log.atWarn().addKeyValue("topic", record.topic()).log("[RETRY] Unexpected payload, skipping");
                return;
            }

            UUID logId = KafkaNotifyUtil.parseLogId(request.notificationLogId());
            // chatId/channel: previously only logId/kafkaTopic were in MDC here — once a message
            // entered the retry ladder, every subsequent log line lost the ability to be grepped
            // by chatId, the more common thing an operator actually has when investigating "why
            // didn't chat X get its notification."
            try (var logCtx   = MDC.putCloseable("logId",      logId != null ? logId.toString() : "");
                 var topicCtx = MDC.putCloseable("kafkaTopic", record.topic());
                 var chatCtx  = MDC.putCloseable("chatId", String.valueOf(request.telegramId()));
                 var chanCtx  = MDC.putCloseable("channel", String.valueOf(request.channel()))) {
                if (logId != null && logService.isAlreadySent(logId)) {
                    log.debug("[RETRY] Already sent — skipping");
                    return;
                }
                try {
                    dispatchService.dispatch(request);
                    if (logId != null) logService.markSent(logId);
                    log.atInfo().addKeyValue("channel", request.channel()).log("[RETRY] Delivered");
                } catch (Exception e) {
                    RetryableNotificationException rne = retryPolicy.classify(e);
                    // DEBUG not WARN — publishToDlq() immediately logs the same failure with the
                    // routing decision; without this, a message failing all 3 retry tiers logged
                    // 6 WARN lines (3 here + 3 in DeadLetterPublisher) for one conceptual failure.
                    log.atDebug()
                       .addKeyValue("retryable", rne.isRetryable())
                       .log("[RETRY] Failed: {}", e.getMessage());
                    deadLetterPublisher.publishToDlq(record, rne);
                }
            }
        } finally {
            ack.acknowledge();
        }
    }

}
