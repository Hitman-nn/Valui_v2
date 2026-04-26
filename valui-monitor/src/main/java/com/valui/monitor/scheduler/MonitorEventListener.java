package com.valui.monitor.scheduler;

import com.valui.common.domain.MatchStatus;
import com.valui.common.dto.MatchDto;
import com.valui.common.event.MatchDiscoveredEvent;
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
 * Bridges the in-process SportEventDetectedEvent to the Kafka MatchDiscoveredEvent.
 * Runs @Async so the publishing transaction completes before Kafka send is attempted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorEventListener {

    static final String TOPIC = "valui.match.discovered";

    private final KafkaTemplate<String, MatchDiscoveredEvent> kafkaTemplate;

    @Async
    @EventListener
    public void on(SportEventDetectedEvent event) {
        String[] parts = event.title() != null ? event.title().split(" - ", 2) : new String[0];
        MatchDto match = new MatchDto(
                event.externalEventId(),
                event.bookmaker(),
                "unknown",
                parts.length > 0 ? parts[0].trim() : event.externalEventId(),
                parts.length > 1 ? parts[1].trim() : "",
                Instant.now(),
                MatchStatus.PREMATCH,
                null, null, null
        );
        MatchDiscoveredEvent kafkaEvent = new MatchDiscoveredEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                match
        );
        kafkaTemplate.send(TOPIC, event.externalEventId(), kafkaEvent)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("Kafka publish failed for event {}: {}", event.externalEventId(), ex.getMessage());
                });
    }
}
