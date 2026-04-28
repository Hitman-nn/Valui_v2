package com.valui.notify.consumer;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.notify.dispatcher.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventConsumer {

    private final NotificationDispatcher dispatcher;

    @KafkaListener(
            topics = KafkaTopics.SPORT_EVENTS_DETECTED,
            groupId = "valui-notify-group"
    )
    public void onSportEventDetected(SportEventDetectedMessage event) {
        log.debug("Received event {} bookmaker={} title='{}'",
                event.eventId(), event.bookmaker(), event.title());
        dispatcher.dispatch(event);
    }
}
