package com.valui.common.kafka;

import java.time.Instant;

/**
 * Kafka message published to the compacted topic {@code subscription.events}.
 * Key = userId ensures log-compaction keeps only the latest plan state per user.
 * Mirrors SubscriptionChangedEvent.avsc.
 */
public record SubscriptionChangedMessage(
        String userId,
        String oldPlan,
        String newPlan,
        Instant changedAt
) {}
