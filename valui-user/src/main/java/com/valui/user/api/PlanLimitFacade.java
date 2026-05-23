package com.valui.user.api;

import com.valui.user.dto.TokenInfoDto;

import java.util.UUID;

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

    /** Возвращает полную информацию о токенах: баланс, история, статистика. */
    TokenInfoDto getTokenInfo(Long telegramId);

    /** Перегрузка для вызовов, где UUID пользователя уже известен (избегает повторной загрузки). */
    TokenInfoDto getTokenInfo(UUID userId);
}
