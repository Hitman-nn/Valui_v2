package com.valui.common.kafka;

import java.time.Instant;

/**
 * Kafka message published to {@code sport.events.detected}.
 * Mirrors SportEventDetected.avsc — JSON-serialised when Schema Registry is absent.
 * Key = controllerId for per-controller ordering within a partition.
 */
public record SportEventDetectedMessage(
        String eventId,
        String controllerId,
        String userId,
        Long telegramId,
        Long chatId,
        String bookmaker,
        String externalEventId,
        String title,
        String url,
        Instant detectedAt
) {}
