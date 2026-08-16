package com.valui.notify.consumer;

import com.valui.common.kafka.ChatDigestMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.notify.sender.TelegramNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Delivers weekly per-chat digest messages to individual Telegram chats.
 * One Kafka record = one recipient (fanout is done by ChatDigestScheduler before publishing).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatDigestConsumer {

    private final TelegramNotificationSender telegramSender;

    @KafkaListener(
            topics = KafkaTopics.CHAT_DIGEST,
            groupId = "${valui.kafka.groups.digest:valui-digest}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ChatDigestMessage message) throws Exception {
        telegramSender.send(message.chatId(), message.text());
        log.debug("[DIGEST] Sent to chatId={}", message.chatId());
    }
}
