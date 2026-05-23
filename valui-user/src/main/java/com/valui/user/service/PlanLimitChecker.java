package com.valui.user.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.UserBkSlotEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.dto.TokenHistoryEntry;
import com.valui.user.dto.TokenInfoDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.TokenTransactionRepository;
import com.valui.user.repository.UserBkSlotRepository;
import com.valui.user.repository.UserRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlanLimitChecker implements PlanLimitFacade {

    private final UserRepository              userRepository;
    private final ControllerRepository        controllerRepository;
    private final TokenLedgerService          tokenLedgerService;
    private final UserBkSlotRepository        userBkSlotRepository;
    private final TokenTransactionRepository  txRepository;

    /**
     * Списывает токены за первый контроллер данной БК.
     * Второй и последующие контроллеры той же БК — бесплатны.
     * Сохраняет UserBkSlotEntity с датой первого списания — планировщик
     * использует её, чтобы не списывать повторно в том же календарном месяце.
     *
     * Слот служит авторитетным источником «уже оплачено», а не счётчик
     * контроллеров — это закрывает race condition при параллельном создании.
     * Уникальный индекс (user_id, bookmaker) гарантирует, что при гонке
     * второй INSERT бросит DataIntegrityViolationException и откатит транзакцию,
     * не допуская двойного списания.
     */
    @Override
    @Transactional
    public void debitForBkSlotIfNew(Long telegramId, String bookmaker) {
        UserEntity user = requireUser(telegramId);
        BookmakerType.valueOf(bookmaker.toUpperCase()); // validate enum

        if (userBkSlotRepository.findByUserIdAndBookmaker(user.getId(), bookmaker).isPresent()) {
            return; // БК-слот уже активен — повторно не списываем
        }

        int cost = tokenLedgerService.getCost("CONTROLLER_BK_MONTHLY");
        tokenLedgerService.debit(user.getId(), cost, TokenReasonCode.CONTROLLER_BK_CHARGE, null);
        userBkSlotRepository.save(
            UserBkSlotEntity.builder()
                .user(user)
                .bookmaker(bookmaker)
                .build()
        );
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
    public TokenInfoDto getTokenInfo(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        UUID userId = user.getId();

        int controllersUsed = controllerRepository.countByUserIdAndIsActiveTrue(userId);

        OffsetDateTime resetAt = user.getTokenStatsResetAt();

        // History: group last 50 transactions by (reasonCode, day), take first 10 groups
        var rawTxs = resetAt != null
            ? txRepository.findTop50ByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(userId, resetAt)
            : txRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        record GroupKey(com.valui.common.domain.TokenReasonCode code, LocalDate date) {}
        Map<GroupKey, int[]> grouped = new LinkedHashMap<>();
        for (var tx : rawTxs) {
            LocalDate day = tx.getCreatedAt().toLocalDate();
            var key = new GroupKey(tx.getReasonCode(), day);
            grouped.computeIfAbsent(key, k -> new int[]{0, 0});
            grouped.get(key)[0] += tx.getDelta();
            grouped.get(key)[1]++;
        }
        List<TokenHistoryEntry> history = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            if (history.size() >= 10) break;
            history.add(new TokenHistoryEntry(e.getKey().code(), e.getKey().date(), e.getValue()[0], e.getValue()[1]));
        }

        // Stats
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime statsFloor = (resetAt != null && resetAt.isAfter(monthStart)) ? resetAt : monthStart;

        long spentThisMonth = Math.abs(txRepository.sumSpentInPeriod(userId, statsFloor, now));
        long spentAllTime   = resetAt != null
            ? Math.abs(txRepository.sumSpentFrom(userId, resetAt))
            : Math.abs(txRepository.sumSpentTotal(userId));

        long avgPerMonth = 0L;
        OffsetDateTime baseDate = resetAt != null ? resetAt : txRepository.findEarliestCreatedAt(userId).orElse(null);
        if (baseDate != null) {
            long months = java.time.temporal.ChronoUnit.MONTHS.between(baseDate, now);
            avgPerMonth = months > 0 ? spentAllTime / months : spentAllTime;
        }

        return new TokenInfoDto(
            controllersUsed,
            user.getTokenBalance() != null ? user.getTokenBalance() : 0,
            user.getTokenMonthlyGrantRef() != null ? user.getTokenMonthlyGrantRef() : 0,
            user.getTokenLowThreshold(),
            history,
            spentThisMonth,
            avgPerMonth,
            spentAllTime
        );
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }
}
