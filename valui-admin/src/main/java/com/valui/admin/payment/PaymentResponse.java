package com.valui.admin.payment;

import java.math.BigDecimal;

public record PaymentResponse(
    String paymentId,
    String confirmationUrl,
    PaymentStatus status,
    BigDecimal amount
) {}
