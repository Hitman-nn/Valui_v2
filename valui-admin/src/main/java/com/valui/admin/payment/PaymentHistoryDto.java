package com.valui.admin.payment;

import com.valui.common.entity.PaymentTransactionEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentHistoryDto(
    String paymentId,
    String planCode,
    BigDecimal amount,
    String currency,
    String status,
    String confirmationUrl,
    OffsetDateTime createdAt
) {
    public static PaymentHistoryDto from(PaymentTransactionEntity tx) {
        return new PaymentHistoryDto(
            tx.getPaymentId(),
            tx.getPlanCode(),
            tx.getAmount(),
            tx.getCurrency(),
            tx.getStatus(),
            tx.getConfirmationUrl(),
            tx.getCreatedAt()
        );
    }
}
