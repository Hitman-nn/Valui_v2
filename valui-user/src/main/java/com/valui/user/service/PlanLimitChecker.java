package com.valui.user.service;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlanLimitChecker {

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ControllerRepository controllerRepository;
    private final GlobalFilterRepository globalFilterRepository;
    private final GroupQuotaService groupQuotaService;

    /**
     * Throws {@link SubscriptionLimitExceededException} if the user's active controller
     * count has reached the plan maximum.
     */
    public void checkControllerLimit(Long telegramId) {
        if (!subscriptionService.canAddController(telegramId)) {
            SubscriptionPlanDto plan = subscriptionService.getUserPlan(telegramId);
            throw new SubscriptionLimitExceededException("controllers", plan.maxControllers());
        }
    }

    /**
     * Throws {@link SubscriptionLimitExceededException} if the group is at its controller capacity.
     * Only call when notificationChatId is a group (negative value).
     */
    public void checkGroupCapacity(Long notificationChatId) {
        groupQuotaService.checkGroupCapacity(notificationChatId);
    }

    /**
     * Throws {@link SubscriptionLimitExceededException} if the bookmaker is not in
     * the user's plan's allowed bookmakers list.
     */
    public void checkBookmakerAccess(Long telegramId, String bookmaker) {
        if (!subscriptionService.canUseBookmaker(telegramId, bookmaker)) {
            SubscriptionPlanDto plan = subscriptionService.getUserPlan(telegramId);
            throw new SubscriptionLimitExceededException(
                "Bookmaker '" + bookmaker + "' is not available on plan '" + plan.code() + "'");
        }
    }

    /**
     * Throws {@link SubscriptionLimitExceededException} if the user's active filter count
     * (controllers with a non-null filter_rule) has reached the plan maximum.
     */
    public void checkFilterLimit(Long telegramId) {
        LimitInfoDto info = getLimitInfo(telegramId);
        if (info.filtersUsed() >= info.filtersMax()) {
            throw new SubscriptionLimitExceededException("filters", info.filtersMax());
        }
    }

    /** Returns full limit snapshot for the user's current plan and usage. */
    @Transactional(readOnly = true)
    public LimitInfoDto getLimitInfo(Long telegramId) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        SubscriptionPlanDto plan = subscriptionService.getUserPlan(telegramId);

        int controllersUsed = controllerRepository.countByUserIdAndIsActiveTrue(user.getId());
        int filtersUsed     = (int) globalFilterRepository.countByUserId(user.getId());

        var expiresAt = subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(user.getId(), SubscriptionStatus.ACTIVE)
            .map(s -> s.getExpiresAt())
            .orElse(null);

        return new LimitInfoDto(
            controllersUsed,
            plan.maxControllers(),
            filtersUsed,
            plan.maxFilters(),
            plan.allowedBookmakers(),
            plan.pollIntervalSec(),
            plan.name(),
            expiresAt,
            user.getTokenBalance() != null ? user.getTokenBalance() : 0
        );
    }
}
