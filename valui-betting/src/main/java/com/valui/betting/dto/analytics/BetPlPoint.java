package com.valui.betting.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BetPlPoint(
        UUID        betId,
        LocalDate   date,
        String      title,
        BigDecimal  stake,
        BigDecimal  pnl,
        BigDecimal  odds,
        String      status
) {}
