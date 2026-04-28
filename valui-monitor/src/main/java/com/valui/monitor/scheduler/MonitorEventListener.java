package com.valui.monitor.scheduler;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.monitor.event.SportEventDetectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Bridges the in-process SportEventDetectedEvent to the Kafka sport.events.detected topic.
 * Runs @Async so the publishing transaction completes before the Kafka send is attempted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorEventListener {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Async
    @EventListener
    public void on(SportEventDetectedEvent event) {
        SportEventDetectedMessage message = new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                event.controllerId().toString(),
                event.userId().toString(),
                event.bookmaker().name(),
                event.title(),
                event.url(),
                Instant.now()
        );
        kafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, event.externalEventId(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka publish failed for event {} (controller {}): {}",
                                event.externalEventId(), event.controllerId(), ex.getMessage());
                    } else {
                        log.debug("Published {} to {} partition {} offset {}",
                                event.externalEventId(),
                                KafkaTopics.SPORT_EVENTS_DETECTED,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
