package com.valui.betting.dto;

import com.valui.common.entity.BankAccountEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record BankAccountDto(
        UUID id,
        long ownerTelegramId,
        String name,
        BigDecimal balance,
        String currency,
        boolean isDefault
) {
    public static BankAccountDto from(BankAccountEntity e) {
        return new BankAccountDto(
                e.getId(), e.getOwnerTelegramId(), e.getName(),
                e.getBalance(), e.getCurrency(), Boolean.TRUE.equals(e.getIsDefault()));
    }
}
