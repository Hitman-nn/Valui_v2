package com.valui.betting.dto;

import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.entity.BetEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BetDto(
        UUID id,
        long telegramId,
        long chatId,
        BetType type,
        BetStatus status,
        BigDecimal totalOdds,
        BigDecimal totalStake,
        BigDecimal potentialPayout,
        BigDecimal actualPayout,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt,
        List<BetSlipDto> slips,
        List<BetParticipantDto> participants
) {
    public static BetDto from(BetEntity e) {
        List<BetSlipDto> slips = e.getSlips() == null ? List.of()
                : e.getSlips().stream().map(BetSlipDto::from).toList();
        List<BetParticipantDto> parts = e.getParticipants() == null ? List.of()
                : e.getParticipants().stream().map(BetParticipantDto::from).toList();
        return new BetDto(
                e.getId(), e.getTelegramId(), e.getChatId(),
                e.getType(), e.getStatus(),
                e.getTotalOdds(), e.getTotalStake(), e.getPotentialPayout(), e.getActualPayout(),
                e.getResolvedAt(), e.getCreatedAt(),
                slips, parts);
    }

    public String mainTitle() {
        if (slips == null || slips.isEmpty()) return "—";
        if (slips.size() == 1) return slips.get(0).matchTitle();
        return slips.get(0).matchTitle() + " +" + (slips.size() - 1) + " leg";
    }
}
