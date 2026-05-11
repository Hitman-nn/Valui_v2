package com.valui.admin.users.dto;

import com.valui.common.entity.PaymentTransactionEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminPaymentTransactionDto(
        UUID id,
        String paymentId,
        BigDecimal amount,
        String currency,
        String status,
        String gateway,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AdminPaymentTransactionDto from(PaymentTransactionEntity e) {
        return new AdminPaymentTransactionDto(
                e.getId(),
                e.getPaymentId(),
                e.getAmount(),
                e.getCurrency(),
                e.getStatus(),
                e.getGateway(),
                e.getDescription(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
