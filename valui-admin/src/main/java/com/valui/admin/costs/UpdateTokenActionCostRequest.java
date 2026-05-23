package com.valui.admin.costs;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTokenActionCostRequest(
    @NotNull @Min(0) Integer costTokens,
    @Size(max = 255) String description
) {}
