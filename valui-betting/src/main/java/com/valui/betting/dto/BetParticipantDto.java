package com.valui.betting.dto;

import com.valui.common.entity.BetParticipantEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record BetParticipantDto(
        UUID id,
        long telegramId,
        String displayName,
        BigDecimal stake,
        BigDecimal profitShare,
        UUID bankAccountId,
        String bankAccountName
) {
    public static BetParticipantDto from(BetParticipantEntity e) {
        return new BetParticipantDto(
                e.getId(), e.getTelegramId(), e.getDisplayName(),
                e.getStake(), e.getProfitShare(),
                e.getBankAccount() != null ? e.getBankAccount().getId() : null,
                e.getBankAccount() != null ? e.getBankAccount().getName() : null);
    }
}
