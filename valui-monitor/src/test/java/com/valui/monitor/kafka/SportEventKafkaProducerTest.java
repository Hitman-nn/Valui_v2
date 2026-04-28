package com.valui.monitor.kafka;

import com.valui.common.domain.BookmakerType;
import com.valui.monitor.event.SportEventDetectedEvent;
import com.valui.monitor.outbox.OutboxSenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SportEventKafkaProducer — unit tests")
class SportEventKafkaProducerTest {

    @Mock OutboxSenderService outboxSenderService;
    @InjectMocks SportEventKafkaProducer producer;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("AFTER_COMMIT: delegates to OutboxSenderService.publishImmediate with correct externalEventId")
    void onSportEventDetected_delegatesToOutboxSender() {
        String externalEventId = "ext-match-42";
        SportEventDetectedEvent event = new SportEventDetectedEvent(
                CTRL_ID, USER_ID, 99L, BookmakerType.FONBET,
                externalEventId, "Spartak - CSKA", "https://fonbet.ru/1");

        producer.onSportEventDetected(event);

        verify(outboxSenderService).publishImmediate(externalEventId);
    }

    @Test
    @DisplayName("AFTER_COMMIT: works when telegramId is null")
    void onSportEventDetected_nullTelegramId_noException() {
        SportEventDetectedEvent event = new SportEventDetectedEvent(
                CTRL_ID, USER_ID, null, BookmakerType.OLIMP,
                "ext-99", "Match X", null);

        producer.onSportEventDetected(event);

        verify(outboxSenderService).publishImmediate("ext-99");
    }
}
