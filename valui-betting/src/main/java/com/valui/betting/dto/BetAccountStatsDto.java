package com.valui.betting.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BetAccountStatsDto(
        UUID accountId,
        String accountName,
        long totalBets,
        long openBets,
        long wonBets,
        long lostBets,
        long returnedBets,
        BigDecimal totalVolume,
        BigDecimal totalPayout,
        BigDecimal profitLoss,
        List<BetPersonBalanceDto> balances
) {}
