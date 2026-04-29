package com.valui.user.service;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.user.dto.SubscriptionPlanDto;

import java.util.List;
import java.util.UUID;

public interface SubscriptionService {

    /** Возвращает активный план пользователя. Результат кешируется. */
    SubscriptionPlanDto getUserPlan(Long telegramId);

    /** Интервал опроса в секундах (из плана). */
    int getPollInterval(Long telegramId);

    /** Все активные планы, отсортированные по цене. Кешируется. */
    List<SubscriptionPlanDto> getAllActivePlans();

    /**
     * Активирует новый план для пользователя.
     * Отменяет текущий, создаёт новый ACTIVE, начисляет разовый token_reward.
     */
    SubscriptionEntity activatePlan(UUID userId, String planCode, String paymentRef);

    /**
     * Истекает подписку и понижает пользователя до FREE.
     * Публикует {@link com.valui.user.event.SubscriptionExpiredEvent}.
     */
    void expireSubscription(UUID subscriptionId);
}
