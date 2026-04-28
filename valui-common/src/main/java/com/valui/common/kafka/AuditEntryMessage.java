package com.valui.common.kafka;

import java.time.Instant;

/**
 * Kafka message published to {@code audit.log} for significant domain actions.
 * Key = userId for partition affinity (all actions of one user go to the same partition).
 * Mirrors AuditEntry.avsc.
 */
public record AuditEntryMessage(
        String userId,
        Long telegramId,
        String action,
        String entityType,
        String entityId,
        String details,
        String ipAddress,
        Instant timestamp
) {}
