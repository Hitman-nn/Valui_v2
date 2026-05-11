package com.valui.user.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.UserBkSlotEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.UserBkSlotRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PlanLimitChecker implements PlanLimitFacade {

    private final UserRepository       userRepository;
    private final ControllerRepository controllerRepository;
    private final TokenLedgerService   tokenLedgerService;
    private final UserBkSlotRepository userBkSlotRepository;

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
    public LimitInfoDto getLimitInfo(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        int controllersUsed = controllerRepository.countByUserIdAndIsActiveTrue(user.getId());
        return new LimitInfoDto(
            controllersUsed,
            user.getTokenBalance() != null ? user.getTokenBalance() : 0,
            user.getTokenMonthlyGrantRef() != null ? user.getTokenMonthlyGrantRef() : 0
        );
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }
}
