package com.valui.monitor.event;

import java.util.UUID;

/**
 * Published when a user's subscription plan changes (upgrade, downgrade, renewal).
 * MonitorScheduler reschedules that user's controllers with the new poll interval.
 */
public record SubscriptionChangedEvent(
        UUID userId,
        Long telegramId,
        String newPlanCode,
        int newPollIntervalSec
) {}
