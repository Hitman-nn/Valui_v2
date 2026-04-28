package com.valui.monitor.scheduler;

import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Direct Kafka publisher for batch event publishing flows.
 * The primary event path goes through MonitorEventListener (Spring @EventListener → Kafka).
 * This bean is used when the caller already has fully-formed SportEventDetectedMessage objects.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishEvents(List<SportEventDetectedMessage> events) {
        events.forEach(event ->
                kafkaTemplate.send(KafkaTopics.SPORT_EVENTS_DETECTED, event.eventId(), event)
                             .whenComplete((r, ex) -> {
                                 if (ex != null) {
                                     log.error("Failed to publish event {} to {}: {}",
                                             event.eventId(), KafkaTopics.SPORT_EVENTS_DETECTED, ex.getMessage());
                                 }
                             })
        );
    }
}
