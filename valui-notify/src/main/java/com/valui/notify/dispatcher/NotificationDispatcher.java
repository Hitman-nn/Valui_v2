package com.valui.notify.dispatcher;

import com.valui.common.kafka.SportEventDetectedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    // TODO: inject valui-bot TelegramSender, load subscribers, filter by bookmaker/sport
    public void dispatch(SportEventDetectedMessage event) {
        log.info("Dispatching notification for event {} bookmaker={} title='{}'",
                event.eventId(), event.bookmaker(), event.title());
    }
}
