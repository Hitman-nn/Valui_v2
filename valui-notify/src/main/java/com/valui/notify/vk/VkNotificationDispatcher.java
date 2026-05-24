package com.valui.notify.vk;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.util.KafkaNotifyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * First-attempt consumer for the VK notification pipeline.
 * Reads from {@code vk.notifications.pending}, dispatches via {@link VkNotificationSender},
 * routes failures through the VK retry ladder via {@link VkDeadLetterPublisher}.
 *
 * Idempotency: a deterministic {@code random_id} derived from {@code notificationLogId}
 * is passed to the VK API so repeated deliveries of the same message are deduplicated
 * by VK itself (within the same conversation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkNotificationDispatcher {

    private final VkNotificationSender  vkSender;
    private final VkDeadLetterPublisher deadLetterPublisher;

    @KafkaListener(
        topics           = KafkaTopics.VK_NOTIFICATIONS_PENDING,
        groupId          = "${valui.kafka.groups.vk-dispatch:valui-vk-dispatch-group}",
        containerFactory = "vkDispatchContainerFactory"
    )
    public void onVkNotificationPending(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("[VK-DISPATCH] Unexpected payload type, skipping");
            return;
        }
        if (request.vkPeerId() == null) {
            log.debug("[VK-DISPATCH] vkPeerId is null, skipping");
            return;
        }

        long randomId = KafkaNotifyUtil.vkRandomId(request.notificationLogId());
        try {
            vkSender.dispatch(request.vkPeerId(), request.messageText(), randomId);
            log.debug("[VK-DISPATCH] Отправлено peerId={} logId={}", request.vkPeerId(), request.notificationLogId());
        } catch (RetryableNotificationException e) {
            log.warn("[VK-DISPATCH] Ошибка peerId={} logId={}: {}", request.vkPeerId(), request.notificationLogId(), e.getMessage());
            deadLetterPublisher.publishToDlq(record, e);
        } catch (Exception e) {
            log.warn("[VK-DISPATCH] Непредвиденная ошибка peerId={}: {}", request.vkPeerId(), e.getMessage());
            deadLetterPublisher.publishToDlq(record,
                    new RetryableNotificationException(e.getMessage(), e, true, 0));
        }
    }

}
