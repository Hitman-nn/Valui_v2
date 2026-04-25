package com.valui.user.service;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.user.dto.SubscriptionPlanDto;

import java.util.UUID;

public interface SubscriptionService {

    /** Returns the active plan for the user. Result is cached for 10 minutes. */
    SubscriptionPlanDto getUserPlan(Long telegramId);

    /** true if the user has not yet reached their plan's controller limit. */
    boolean canAddController(Long telegramId);

    /** true if the bookmaker code is included in the user's plan allowedBookmakers. */
    boolean canUseBookmaker(Long telegramId, String bookmaker);

    /** Returns the plan's polling interval in seconds (default 120 for FREE). */
    int getPollInterval(Long telegramId);

    /**
     * Activates a new plan for the user.
     * Cancels any existing active subscription and creates a new ACTIVE one.
     */
    SubscriptionEntity activatePlan(UUID userId, String planCode, String paymentRef);

    /**
     * Marks a subscription as EXPIRED and downgrades the user to FREE.
     * Publishes {@link com.valui.user.event.SubscriptionExpiredEvent}.
     */
    void expireSubscription(UUID subscriptionId);
}
