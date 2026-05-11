package com.valui.user.api;

import com.valui.user.dto.LimitInfoDto;

/**
 * Публичный API valui-user для токенного биллинга.
 * Внешние модули (valui-bot) должны использовать этот интерфейс.
 */
public interface PlanLimitFacade {

    /**
     * Списывает токены за первый контроллер данной БК у пользователя.
     * Если у пользователя уже есть активный контроллер этой БК — ничего не списывает.
     * Бросает {@link com.valui.common.exception.InsufficientTokensException} при нехватке токенов.
     */
    void debitForBkSlotIfNew(Long telegramId, String bookmaker);

    /**
     * Списывает токены за добавление фильтра на контроллер.
     * Бросает {@link com.valui.common.exception.InsufficientTokensException} при нехватке токенов.
     */
    void debitForControllerFilter(Long telegramId);

    /** Возвращает снимок баланса токенов и использования контроллеров. */
    LimitInfoDto getLimitInfo(Long telegramId);
}
