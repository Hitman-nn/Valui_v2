package com.valui.betting.dto;

import java.math.BigDecimal;

public record BetStatsDto(
        long totalBets,
        long openBets,
        long wonBets,
        long lostBets,
        long returnedBets,
        BigDecimal totalStaked,
        BigDecimal totalPayout,
        BigDecimal profitLoss,   // totalPayout - totalStaked
        double roi               // profitLoss / totalStaked * 100
) {}
