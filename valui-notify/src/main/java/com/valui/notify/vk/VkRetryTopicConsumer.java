package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.util.KafkaNotifyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles the three time-delayed VK retry tiers (1 s, 5 s, 30 s).
 *
 * The Kafka poll thread returns immediately; the actual retry runs on a dedicated
 * scheduler thread after the configured delay. The offset is acknowledged only
 * after the retry completes, so a crash during the wait causes re-delivery on restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkRetryTopicConsumer {

    private final VkNotificationSender  vkSender;
    private final VkDeadLetterPublisher deadLetterPublisher;
    private final ScheduledExecutorService vkRetryScheduler;

    private static final long DELAY_1S  =  1_000L;
    private static final long DELAY_5S  =  5_000L;
    private static final long DELAY_30S = 30_000L;

    @KafkaListener(
            topics           = KafkaTopics.VK_NOTIFICATIONS_RETRY_1S,
            groupId          = "${valui.kafka.groups.vk-retry:valui-vk-retry-group}",
            containerFactory = "vkRetryContainerFactory"
    )
    public void handle1s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        vkRetryScheduler.schedule(() -> doProcess(record, ack), DELAY_1S, TimeUnit.MILLISECONDS);
    }

    @KafkaListener(
            topics           = KafkaTopics.VK_NOTIFICATIONS_RETRY_5S,
            groupId          = "${valui.kafka.groups.vk-retry:valui-vk-retry-group}",
            containerFactory = "vkRetryContainerFactory"
    )
    public void handle5s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        vkRetryScheduler.schedule(() -> doProcess(record, ack), DELAY_5S, TimeUnit.MILLISECONDS);
    }

    @KafkaListener(
            topics           = KafkaTopics.VK_NOTIFICATIONS_RETRY_30S,
            groupId          = "${valui.kafka.groups.vk-retry:valui-vk-retry-group}",
            containerFactory = "vkRetryContainerFactory"
    )
    public void handle30s(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        vkRetryScheduler.schedule(() -> doProcess(record, ack), DELAY_30S, TimeUnit.MILLISECONDS);
    }

    private void doProcess(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        try {
            if (!(record.value() instanceof UserNotificationRequestMessage request)) {
                log.atWarn().addKeyValue("topic", record.topic()).log("[VK-RETRY] Unexpected payload, skipping");
                return;
            }
            if (request.vkPeerId() == null) {
                log.atWarn().addKeyValue("topic", record.topic())
                   .addKeyValue("logId", request.notificationLogId())
                   .log("[VK-RETRY] vkPeerId is null — anomaly, skipping");
                return;
            }

            try (var logCtx   = MDC.putCloseable("logId",      request.notificationLogId() != null ? request.notificationLogId() : "");
                 var peerCtx  = MDC.putCloseable("vkPeerId",   request.vkPeerId().toString());
                 var topicCtx = MDC.putCloseable("kafkaTopic", record.topic())) {
                long randomId = KafkaNotifyUtil.vkRandomId(request.notificationLogId());
                try {
                    vkSender.dispatch(request.vkPeerId(), request.messageText(), randomId);
                    log.info("[VK-RETRY] Delivered");
                } catch (RetryableNotificationException e) {
                    log.atDebug().addKeyValue("retryable", e.isRetryable())
                       .log("[VK-RETRY] Failed: {}", e.getMessage());
                    deadLetterPublisher.publishToDlq(record, e);
                } catch (Exception e) {
                    log.atDebug().addKeyValue("errorType", e.getClass().getSimpleName())
                       .log("[VK-RETRY] Unexpected error: {}", e.getMessage());
                    deadLetterPublisher.publishToDlq(record,
                            new RetryableNotificationException(e.getMessage(), e, true, 0));
                }
            }
        } finally {
            ack.acknowledge();
        }
    }
}
