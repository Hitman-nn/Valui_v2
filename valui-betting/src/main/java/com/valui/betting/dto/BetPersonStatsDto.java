package com.valui.betting.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record BetPersonStatsDto(
        UUID personId,
        String displayName,
        long totalBets,
        long openBets,
        long wonBets,
        long lostBets,
        long returnedBets,
        BigDecimal totalStaked,
        BigDecimal totalPayout,
        BigDecimal profitLoss,
        double roi
) {}
