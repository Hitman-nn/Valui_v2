package com.valui.betting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BetSlipRequest(
        @NotBlank String matchTitle,
        String matchUrl,
        String bookmaker,
        @NotNull @DecimalMin("1.01") BigDecimal odds
) {}
