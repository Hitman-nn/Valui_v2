package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.util.KafkaNotifyUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
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
public class VkDlqConsumer {

    private final VkNotificationSender     vkSender;
    private final VkDeadLetterPublisher    deadLetterPublisher;
    private final ScheduledExecutorService vkDlqRetryScheduler;
    private final long                     delayMs;

    public VkDlqConsumer(VkNotificationSender vkSender,
                         VkDeadLetterPublisher deadLetterPublisher,
                         ScheduledExecutorService vkDlqRetryScheduler,
                         @Value("${valui.vk.dlq-delay-ms:300000}") long delayMs) {
        this.vkSender             = vkSender;
        this.deadLetterPublisher  = deadLetterPublisher;
        this.vkDlqRetryScheduler  = vkDlqRetryScheduler;
        this.delayMs              = delayMs;
    }

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
                log.atWarn().addKeyValue("logId", request.notificationLogId())
                   .log("[VK-DLQ] vkPeerId is null — anomaly, skipping");
                return;
            }

            try (var logCtx   = MDC.putCloseable("logId",      request.notificationLogId() != null ? request.notificationLogId() : "");
                 var peerCtx  = MDC.putCloseable("vkPeerId",   request.vkPeerId().toString());
                 var topicCtx = MDC.putCloseable("kafkaTopic", record.topic())) {
                long randomId = KafkaNotifyUtil.vkRandomId(request.notificationLogId());
                try {
                    vkSender.dispatch(request.vkPeerId(), request.messageText(), randomId);
                    log.info("[VK-DLQ] 5-min retry succeeded");
                } catch (RetryableNotificationException e) {
                    // DEBUG not ERROR — publishToDlq() immediately logs this same terminal
                    // failure at ERROR with setCause(ex), same exception either way.
                    log.atDebug().log("[VK-DLQ] Final attempt failed: {}", e.getMessage());
                    deadLetterPublisher.publishToDlq(record, e);
                } catch (Exception e) {
                    log.atDebug().addKeyValue("errorType", e.getClass().getSimpleName())
                       .log("[VK-DLQ] Unexpected error: {}", e.getMessage());
                    deadLetterPublisher.publishToDlq(record,
                            new RetryableNotificationException(e.getMessage(), e, true, 0));
                }
            }
        } finally {
            ack.acknowledge();
        }
    }
}
