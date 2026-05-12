package com.valui.monitor.kafka;

import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.monitor.event.SportEventDetectedEvent;
import com.valui.monitor.outbox.OutboxEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class SportEventMapper {

    public SportEventDetectedMessage toMessage(SportEventDetectedEvent event) {
        return new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                event.controllerId().toString(),
                event.userId().toString(),
                event.telegramId(),
                event.chatId(),
                event.bookmaker().name(),
                event.externalEventId(),
                event.title(),
                event.url(),
                Instant.now(),
                event.extraData()
        );
    }

    public SportEventDetectedMessage fromOutbox(OutboxEvent outbox) {
        return new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                outbox.getControllerId(),
                outbox.getUserId(),
                outbox.getTelegramId(),
                outbox.getChatId(),
                outbox.getBookmaker(),
                outbox.getExternalEventId(),
                outbox.getTitle(),
                outbox.getUrl(),
                Instant.now(),
                outbox.getExtraData()
        );
    }
}
