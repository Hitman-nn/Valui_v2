package com.valui.betting.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BalancePoint(
        LocalDate date,
        BigDecimal pnl,
        BigDecimal cumulative
) {}
