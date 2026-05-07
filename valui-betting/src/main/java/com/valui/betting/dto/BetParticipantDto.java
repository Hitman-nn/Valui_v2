package com.valui.betting.dto;

import com.valui.common.entity.BetParticipantEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record BetParticipantDto(
        UUID id,
        UUID personId,
        String displayName,
        BigDecimal stake,
        BigDecimal profitShare
) {
    public static BetParticipantDto from(BetParticipantEntity e) {
        return new BetParticipantDto(
                e.getId(),
                e.getPerson() != null ? e.getPerson().getId() : null,
                e.getDisplayName(),
                e.getStake(),
                e.getProfitShare());
    }
}
