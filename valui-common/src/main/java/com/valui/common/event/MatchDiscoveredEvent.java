package com.valui.common.event;

import com.valui.common.dto.MatchDto;

import java.time.Instant;

/**
 * Published to Kafka topic "valui.match.discovered" when a new match is detected.
 * Consumed by valui-notify to fan out Telegram notifications to subscribers.
 */
public record MatchDiscoveredEvent(
        String eventId,
        Instant occurredAt,
        MatchDto match
) {}