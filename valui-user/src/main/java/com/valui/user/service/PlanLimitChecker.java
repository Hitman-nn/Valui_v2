package com.valui.user.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.UserBkSlotEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserBkSlotRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlanLimitChecker implements PlanLimitFacade {

    private final SubscriptionService    subscriptionService;
    private final UserRepository         userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ControllerRepository   controllerRepository;
    private final GlobalFilterRepository globalFilterRepository;
    private final TokenLedgerService     tokenLedgerService;
    private final UserBkSlotRepository   userBkSlotRepository;

    /**
     * Списывает токены за первый контроллер данной БК.
     * Второй и последующие контроллеры той же БК — бесплатны.
     * Сохраняет UserBkSlotEntity с датой первого списания — планировщик
     * использует её, чтобы не списывать повторно в том же календарном месяце.
     */
    @Override
    @Transactional
    public void debitForBkSlotIfNew(Long telegramId, String bookmaker) {
        UserEntity user = requireUser(telegramId);
        BookmakerType bk = BookmakerType.valueOf(bookmaker.toUpperCase());

        int existingCount = controllerRepository.countByUserIdAndBookmakerAndIsActiveTrue(user.getId(), bk);
        if (existingCount == 0) {
            int cost = tokenLedgerService.getCost("CONTROLLER_BK_MONTHLY");
            tokenLedgerService.debit(user.getId(), cost, TokenReasonCode.CONTROLLER_BK_CHARGE, null);

            // Фиксируем дату первого списания для планировщика
            userBkSlotRepository.findByUserIdAndBookmaker(user.getId(), bookmaker)
                .orElseGet(() -> userBkSlotRepository.save(
                    UserBkSlotEntity.builder()
                        .user(user)
                        .bookmaker(bookmaker)
                        .build()
                ));
        }
    }

    /**
     * Списывает токены за добавление фильтра на контроллер.
     */
    @Override
    @Transactional
    public void debitForControllerFilter(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        int cost = tokenLedgerService.getCost("CONTROLLER_FILTER_MONTHLY");
        tokenLedgerService.debit(user.getId(), cost, TokenReasonCode.CONTROLLER_FILTER_CHARGE, null);
    }

    @Override
    @Transactional(readOnly = true)
    public LimitInfoDto getLimitInfo(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        SubscriptionPlanDto plan = subscriptionService.getUserPlan(telegramId);

        int controllersUsed = controllerRepository.countByUserIdAndIsActiveTrue(user.getId());
        int filtersUsed     = (int) globalFilterRepository.countByUserId(user.getId());

        var expiresAt = subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(user.getId(), SubscriptionStatus.ACTIVE)
            .map(s -> s.getExpiresAt())
            .orElse(null);

        return new LimitInfoDto(
            controllersUsed,
            Integer.MAX_VALUE,
            filtersUsed,
            Integer.MAX_VALUE,
            plan.allowedBookmakers(),
            plan.pollIntervalSec(),
            plan.name(),
            expiresAt,
            user.getTokenBalance() != null ? user.getTokenBalance() : 0,
            plan.monthlyTokenGrant(),
            plan.topupDiscountPct()
        );
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }
}
