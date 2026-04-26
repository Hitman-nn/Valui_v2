package com.valui.monitor.event;

import com.valui.common.domain.BookmakerType;

import java.util.UUID;

/**
 * Domain event published when a new sports event is detected for a controller.
 * Consumed by MonitorEventListener which bridges to the Kafka MatchDiscoveredEvent.
 */
public record SportEventDetectedEvent(
        UUID controllerId,
        UUID userId,
        Long telegramId,
        BookmakerType bookmaker,
        String externalEventId,
        String title,
        String url
) {}
