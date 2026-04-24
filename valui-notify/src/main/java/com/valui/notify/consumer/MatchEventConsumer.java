package com.valui.notify.consumer;

import com.valui.common.event.MatchDiscoveredEvent;
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
            topics = "valui.match.discovered",
            groupId = "${spring.kafka.consumer.group-id:valui-notify}",
            containerFactory = "matchEventListenerContainerFactory"
    )
    public void onMatchDiscovered(MatchDiscoveredEvent event) {
        log.debug("Received event {} for match {}", event.eventId(), event.match().id());
        dispatcher.dispatch(event);
    }
}