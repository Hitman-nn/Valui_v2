package com.valui.admin.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(
    UUID userId,
    Long telegramId,
    String planCode,
    BigDecimal amount,
    String currency,
    String description,
    String returnUrl
) {}
