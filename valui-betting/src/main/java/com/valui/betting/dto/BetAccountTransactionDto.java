package com.valui.betting.dto;

import com.valui.common.entity.BetAccountTransactionEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BetAccountTransactionDto(
        UUID id,
        String personName,
        BigDecimal amount,
        OffsetDateTime createdAt
) {
    public static BetAccountTransactionDto from(BetAccountTransactionEntity e) {
        return new BetAccountTransactionDto(
                e.getId(),
                e.getPerson().getDisplayName(),
                e.getAmount(),
                e.getCreatedAt());
    }
}
