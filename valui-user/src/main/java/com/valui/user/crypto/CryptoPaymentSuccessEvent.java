package com.valui.user.crypto;

import java.math.BigDecimal;

/** Публикуется после подтверждения оплаты — бот слушает и уведомляет пользователя. */
public record CryptoPaymentSuccessEvent(
    long   telegramId,
    int    tokenAmount,
    String currency,
    BigDecimal cryptoAmount
) {}
