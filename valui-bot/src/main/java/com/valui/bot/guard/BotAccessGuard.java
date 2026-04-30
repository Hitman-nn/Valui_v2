package com.valui.bot.guard;

import com.valui.common.exception.InsufficientTokensException;
import com.valui.user.api.PlanLimitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Проверяет токенный баланс перед операциями бота.
 * При нехватке токенов бросает {@link InsufficientTokensException} —
 * UI-уведомление остаётся на стороне вызывающего callback-обработчика.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotAccessGuard {

    private final PlanLimitFacade planLimitFacade;

    /** Проверяет наличие токенов для нового BK-слота. */
    public void guardAddController(Long fromId, String bookmaker) {
        try {
            planLimitFacade.debitForBkSlotIfNew(fromId, bookmaker);
        } catch (InsufficientTokensException e) {
            log.info("[GUARD] Нехватка токенов: fromId={}", fromId);
            throw e;
        }
    }

    /** Проверяет наличие токенов для добавления фильтра на контроллер. */
    public void guardAddFilter(Long fromId) {
        try {
            planLimitFacade.debitForControllerFilter(fromId);
        } catch (InsufficientTokensException e) {
            log.info("[GUARD] Нехватка токенов: fromId={}", fromId);
            throw e;
        }
    }
}
