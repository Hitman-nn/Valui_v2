package com.valui.user.event;

import java.util.UUID;

/**
 * Published when a paid subscription expires and the user is downgraded to FREE.
 * Consumed by notification dispatcher (valui-notify / valui-bot) to alert the user.
 */
public record SubscriptionExpiredEvent(
    UUID userId,
    Long telegramId,
    String oldPlanCode
) {}
