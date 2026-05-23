package com.valui.admin.crypto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateExchangeRateRequest(
    @NotNull @DecimalMin("0.00000001") BigDecimal tokensPerUnit
) {}
