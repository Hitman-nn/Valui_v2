package com.valui.betting.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

public record AnalyticsResponse(
        List<BalancePoint> balanceDynamics,
        List<BetPlPoint>   plPoints,
        DistributionData   distribution,
        Summary            summary
) {
    public record Summary(
            long       totalBets,
            long       openBets,
            long       wonBets,
            long       lostBets,
            long       returnedBets,
            BigDecimal totalStaked,
            BigDecimal totalPnl,
            double     roi
    ) {}
}
