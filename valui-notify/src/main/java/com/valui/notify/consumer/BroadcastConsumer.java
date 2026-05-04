package com.valui.notify.consumer;

import com.valui.common.kafka.AdminBroadcastMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.sender.TelegramNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Delivers admin broadcast messages to individual Telegram chats.
 * One Kafka record = one recipient (fanout is done by the admin API before publishing).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastConsumer {

    private final TelegramNotificationSender telegramSender;

    @KafkaListener(
            topics = KafkaTopics.ADMIN_BROADCAST,
            groupId = "valui-broadcast",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(AdminBroadcastMessage message) {
        try {
            telegramSender.send(message.chatId(), message.text());
            log.debug("[BROADCAST] Sent to chatId={}", message.chatId());
        } catch (Exception e) {
            log.error("[BROADCAST] Failed to deliver to chatId={}: {}", message.chatId(), e.getMessage());
        }
    }
}
