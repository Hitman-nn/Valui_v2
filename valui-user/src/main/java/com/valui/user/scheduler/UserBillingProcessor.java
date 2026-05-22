package com.valui.user.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.entity.UserBkSlotEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.UserBkSlotRepository;
import com.valui.user.repository.UserRepository;
import com.valui.user.service.TokenLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Выполняет биллинг одного пользователя в отдельной транзакции.
 * Вынесен из MonthlyTokenBillingScheduler, чтобы @Transactional
 * проходил через Spring-прокси (self-invocation не перехватывается).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBillingProcessor {

    private final UserRepository         userRepository;
    private final ControllerRepository   controllerRepository;
    private final GlobalFilterRepository globalFilterRepository;
    private final UserBkSlotRepository   userBkSlotRepository;
    private final TokenLedgerService     tokenLedgerService;

    @Transactional
    public MonthlyTokenBillingScheduler.BillingResult process(UserEntity user, YearMonth billingMonth) {
        int granted = 0, bkCharged = 0, filterCharged = 0;

        // 1. Начисляем ежемесячный грант (задаётся админом через tokenMonthlyGrantRef)
        int grant = user.getTokenMonthlyGrantRef() != null ? user.getTokenMonthlyGrantRef() : 0;
        if (grant > 0) {
            user.setTokenAlertSent(false);
            userRepository.save(user);
            tokenLedgerService.credit(user.getId(), grant, TokenReasonCode.PLAN_GRANT, null);
            granted = grant;
            log.debug("[BILLING] Грант userId={} +{}", user.getId(), grant);
        }

        // 3. Списываем за БК-слоты
        int bkCost = tokenLedgerService.getCost("CONTROLLER_BK_MONTHLY");
        List<UserBkSlotEntity> bkSlots = userBkSlotRepository.findAllByUserId(user.getId());

        for (UserBkSlotEntity slot : bkSlots) {
            if (YearMonth.from(slot.getFirstChargedAt()).equals(billingMonth)) {
                log.debug("[BILLING] БК-слот {} userId={} пропущен (текущий месяц)", slot.getBookmaker(), user.getId());
                continue;
            }

            BookmakerType bk;
            try {
                bk = BookmakerType.valueOf(slot.getBookmaker());
            } catch (IllegalArgumentException e) {
                continue;
            }

            int activeCount = controllerRepository.countByUserIdAndBookmakerAndIsActiveTrue(user.getId(), bk);
            if (activeCount == 0) {
                userBkSlotRepository.delete(slot);
                continue;
            }

            boolean ok = tokenLedgerService.tryDebit(
                user.getId(), bkCost, TokenReasonCode.MONTHLY_BK_CHARGE, null);
            if (!ok) {
                pauseBookmakerControllers(user.getId(), bk);
                log.info("[BILLING] Нет токенов для БК {} userId={} → пауза", slot.getBookmaker(), user.getId());
            } else {
                bkCharged++;
            }
        }

        // 4. Списываем за глобальные фильтры (только активные — паузированные не трогаем)
        int filterCost = tokenLedgerService.getCost("FILTER_MONTHLY");
        List<GlobalFilterEntity> filters = globalFilterRepository
            .findAllByUserIdAndPausedByTokensFalseOrderByCreatedAtAsc(user.getId());

        for (GlobalFilterEntity filter : filters) {
            if (YearMonth.from(filter.getCreatedAt()).equals(billingMonth)) {
                log.debug("[BILLING] Фильтр {} userId={} пропущен (текущий месяц)", filter.getId(), user.getId());
                continue;
            }

            boolean ok = tokenLedgerService.tryDebit(
                user.getId(), filterCost, TokenReasonCode.MONTHLY_FILTER_CHARGE, filter.getId());
            if (!ok) {
                filter.setPausedByTokens(true);
                globalFilterRepository.save(filter);
                log.info("[BILLING] Нет токенов для фильтра {} userId={} → пауза", filter.getId(), user.getId());
            } else {
                filterCharged++;
            }
        }

        // 5. Списываем за фильтры на контроллерах (только не приостановленные)
        int ctrlFilterCost = tokenLedgerService.getCost("CONTROLLER_FILTER_MONTHLY");
        List<ControllerEntity> controllersWithFilter = controllerRepository
            .findAllByUserIdAndIsActiveTrue(user.getId()).stream()
            .filter(c -> c.getFilterRule() != null && !c.getFilterRule().isBlank()
                      && !Boolean.TRUE.equals(c.getFilterPausedByTokens()))
            .toList();

        for (ControllerEntity c : controllersWithFilter) {
            if (c.getFilterSetAt() != null
                    && YearMonth.from(c.getFilterSetAt()).equals(billingMonth)) {
                log.debug("[BILLING] Фильтр контроллера {} userId={} пропущен (текущий месяц)", c.getId(), user.getId());
                continue;
            }

            boolean ok = tokenLedgerService.tryDebit(
                user.getId(), ctrlFilterCost, TokenReasonCode.MONTHLY_CONTROLLER_FILTER_CHARGE, c.getId());
            if (!ok) {
                c.setFilterPausedByTokens(true);
                controllerRepository.save(c);
                log.info("[BILLING] Нет токенов для фильтра контроллера {} userId={} → пауза", c.getId(), user.getId());
            } else {
                filterCharged++;
            }
        }

        return new MonthlyTokenBillingScheduler.BillingResult(granted, bkCharged, filterCharged);
    }

    private void pauseBookmakerControllers(UUID userId, BookmakerType bookmaker) {
        List<ControllerEntity> bkControllers = controllerRepository
            .findAllByUserIdAndBookmakerAndIsActiveTrue(userId, bookmaker);
        for (ControllerEntity c : bkControllers) {
            controllerRepository.updateTokenPauseState(c.getId(), true, false, true);
        }
    }
}
