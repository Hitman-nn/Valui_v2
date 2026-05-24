package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.util.KafkaNotifyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 4th and final VK retry tier: 5-minute delay before the last dispatch attempt.
 * If this attempt fails, the record is routed to {@code vk.notifications.dlq.final}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkDlqConsumer {

    /** Package-private to allow zero-delay override in unit tests. */
    long delayMs = 5 * 60 * 1_000L;

    private final VkNotificationSender     vkSender;
    private final VkDeadLetterPublisher    deadLetterPublisher;
    private final ScheduledExecutorService vkDlqRetryScheduler;

    @KafkaListener(
            topics           = KafkaTopics.VK_NOTIFICATIONS_DLQ,
            groupId          = "${valui.kafka.groups.vk-dlq:valui-vk-dlq-group}",
            containerFactory = "vkDlqContainerFactory"
    )
    public void handleDlq(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        vkDlqRetryScheduler.schedule(() -> doProcess(record, ack), delayMs, TimeUnit.MILLISECONDS);
    }

    private void doProcess(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof UserNotificationRequestMessage request)) {
                log.warn("[VK-DLQ] Unexpected payload type, skipping");
                return;
            }
            if (request.vkPeerId() == null) {
                log.warn("[VK-DLQ] vkPeerId is null logId={} — anomaly, skipping",
                        request.notificationLogId());
                return;
            }

            long randomId = KafkaNotifyUtil.vkRandomId(request.notificationLogId());
            try {
                vkSender.dispatch(request.vkPeerId(), request.messageText(), randomId);
                log.info("[VK-DLQ] 5-min retry succeeded peerId={} logId={}", request.vkPeerId(), request.notificationLogId());
            } catch (RetryableNotificationException e) {
                log.error("[VK-DLQ] Final attempt failed peerId={} logId={}: {}", request.vkPeerId(), request.notificationLogId(), e.getMessage());
                deadLetterPublisher.publishToDlq(record, e);
            } catch (Exception e) {
                log.error("[VK-DLQ] Непредвиденная ошибка peerId={}: {}", request.vkPeerId(), e.getMessage());
                deadLetterPublisher.publishToDlq(record,
                        new RetryableNotificationException(e.getMessage(), e, true, 0));
            }
        } finally {
            ack.acknowledge();
        }
    }
}
