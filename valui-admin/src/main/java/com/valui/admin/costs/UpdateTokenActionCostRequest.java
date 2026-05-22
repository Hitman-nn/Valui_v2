package com.valui.admin.costs;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateTokenActionCostRequest(
    @NotNull @Min(0) Integer costTokens,
    String description
) {}
