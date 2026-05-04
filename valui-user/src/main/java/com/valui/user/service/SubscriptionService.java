package com.valui.user.service;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.user.dto.PlanStatsDto;
import com.valui.user.dto.SubscriptionPlanDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
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

    // ── Admin-only ────────────────────────────────────────────────────────────

    /** Paginated list of all ACTIVE subscriptions with plan and user details. */
    Page<SubscriptionEntity> findAllActive(Pageable pageable);

    /** Subscriptions expiring within the given window. */
    List<SubscriptionEntity> findExpiringSoon(OffsetDateTime from, OffsetDateTime to);

    /** Count of active subscriptions grouped by plan. */
    List<PlanStatsDto> getStatsByPlan();

    /** Admin: grant a plan to a user (no payment required). */
    void grantPlan(UUID userId, String planCode);

    /** Returns telegramIds of all active subscribers of the given plan (null = ALL plans). */
    List<Long> findActiveTelegramIdsByPlan(String planCode);
}
