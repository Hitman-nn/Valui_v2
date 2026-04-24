package com.valui.monitor.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.common.dto.MatchDto;
import com.valui.common.event.MatchDiscoveredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchEventPublisher {

    static final String TOPIC = "valui.match.discovered";

    private final KafkaTemplate<String, MatchDiscoveredEvent> kafkaTemplate;

    public void publishNewMatches(BookmakerType bookmaker, List<MatchDto> matches) {
        matches.forEach(match -> {
            var event = new MatchDiscoveredEvent(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    match
            );
            kafkaTemplate.send(TOPIC, match.id(), event)
                         .whenComplete((r, ex) -> {
                             if (ex != null) log.error("Failed to publish event for match {}", match.id(), ex);
                         });
        });
    }
}