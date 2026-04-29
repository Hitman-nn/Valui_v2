package com.valui.user.service;

import com.valui.common.domain.TokenReasonCode;

import java.util.UUID;

/**
 * Центральная точка управления токенным балансом.
 * Все списания и начисления проходят только через этот сервис.
 */
public interface TokenLedgerService {

    /** Получить текущий баланс по UUID пользователя. */
    int getBalance(UUID userId);

    /** Получить текущий баланс по Telegram ID. */
    int getBalance(Long telegramId);

    /** Получить стоимость действия из БД. */
    int getCost(String actionCode);

    /**
     * Начислить токены (баланс не может упасть ниже нуля при начислении).
     * Если были контроллеры на паузе из-за токенов — восстанавливает их.
     */
    int credit(UUID userId, int amount, TokenReasonCode reason, UUID refId);

    int credit(Long telegramId, int amount, TokenReasonCode reason, UUID refId);

    /**
     * Списать токены. Бросает {@link com.valui.common.exception.InsufficientTokensException}
     * если баланс меньше amount. При падении до нуля — приостанавливает все контроллеры.
     */
    int debit(UUID userId, int amount, TokenReasonCode reason, UUID refId);

    int debit(Long telegramId, int amount, TokenReasonCode reason, UUID refId);

    /**
     * Попытка списать токены. Возвращает true если успешно, false если недостаточно токенов.
     * Не бросает исключений. При падении до нуля — приостанавливает все контроллеры.
     */
    boolean tryDebit(UUID userId, int amount, TokenReasonCode reason, UUID refId);

    boolean tryDebit(Long telegramId, int amount, TokenReasonCode reason, UUID refId);

    /** Восстановить контроллеры, приостановленные из-за нехватки токенов. */
    void restoreTokenPausedControllers(UUID userId);

    /** Приостановить все активные контроллеры пользователя (паузы из-за токенов). */
    void pauseAllControllers(UUID userId);
}
