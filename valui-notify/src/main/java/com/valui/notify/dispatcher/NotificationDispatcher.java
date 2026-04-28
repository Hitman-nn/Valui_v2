package com.valui.notify.dispatcher;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
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
 * First-attempt consumer: reads from {@code user.notifications.pending}.
 *
 * On success marks the log SENT.
 * On failure classifies the exception via {@link NotificationRetryPolicy} and
 * routes to the retry ladder via {@link DeadLetterPublisher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService logService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final NotificationRetryPolicy retryPolicy;

    @KafkaListener(
            topics           = KafkaTopics.USER_NOTIFICATIONS_PENDING,
            groupId          = "valui-notify-dispatch-group",
            containerFactory = "dispatchContainerFactory"
    )
    public void onNotificationPending(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("[DISPATCH] Unexpected payload type, skipping");
            return;
        }

        UUID logId = parseLogId(request.notificationLogId());
        try {
            dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId);
            log.debug("[DISPATCH] Sent [logId={} channel={}]", logId, request.channel());
        } catch (Exception e) {
            log.warn("[DISPATCH] Failed [logId={} channel={} userId={}]: {}",
                    logId, request.channel(), request.userId(), e.getMessage());
            if (logId != null) logService.markFailed(logId, e.getMessage());
            RetryableNotificationException rne = retryPolicy.classify(e);
            deadLetterPublisher.publishToDlq(record, rne);
        }
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
