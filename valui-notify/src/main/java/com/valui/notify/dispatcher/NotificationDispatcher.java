package com.valui.notify.dispatcher;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.log.NotificationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Second-stage consumer: reads from {@code user.notifications.pending} and
 * dispatches to the appropriate channel sender.
 *
 * On failure: updates the log row to FAILED and forwards to {@code notifications.dlq}
 * for retry by {@link com.valui.notify.consumer.DlqConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService      logService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(
            topics          = KafkaTopics.USER_NOTIFICATIONS_PENDING,
            groupId         = "valui-notify-dispatch-group",
            containerFactory = "dispatchContainerFactory"
    )
    public void onNotificationPending(UserNotificationRequestMessage request) {
        UUID logId = parseLogId(request.notificationLogId());
        try {
            dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId);
            log.debug("Notification dispatched [logId={} channel={}]", logId, request.channel());
        } catch (Exception e) {
            log.error("Dispatch failed [logId={} channel={} userId={}]: {}",
                    logId, request.channel(), request.userId(), e.getMessage());
            if (logId != null) logService.markFailed(logId, e.getMessage());
            forwardToDlq(request);
        }
    }

    private void forwardToDlq(UserNotificationRequestMessage request) {
        ProducerRecord<String, Object> dlqRecord =
                new ProducerRecord<>(KafkaTopics.NOTIFICATIONS_DLQ, request.userId(), request);
        dlqRecord.headers().add("x-retry-count", "0".getBytes());
        kafkaTemplate.send(dlqRecord)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("Failed to forward to DLQ: {}", ex.getMessage());
                });
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
