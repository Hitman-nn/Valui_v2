package com.valui.notify.dispatcher;

import com.valui.common.event.MatchDiscoveredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    public void dispatch(MatchDiscoveredEvent event) {
        // TODO: load subscribers, filter by bookmaker/sport, send via valui-bot
        log.info("Dispatching notification for match {}", event.match().id());
    }
}