package com.valui.betting.dto;

import com.valui.common.domain.SlipResult;
import com.valui.common.entity.BetSlipEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BetSlipDto(
        UUID id,
        String matchTitle,
        String matchUrl,
        String bookmaker,
        BigDecimal odds,
        SlipResult result,
        OffsetDateTime resolvedAt,
        int sortOrder
) {
    public static BetSlipDto from(BetSlipEntity e) {
        return new BetSlipDto(
                e.getId(), e.getMatchTitle(), e.getMatchUrl(),
                e.getBookmaker(), e.getOdds(), e.getResult(),
                e.getResolvedAt(), e.getSortOrder());
    }
}
