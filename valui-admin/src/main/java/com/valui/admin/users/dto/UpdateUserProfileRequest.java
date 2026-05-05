package com.valui.admin.users.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateUserProfileRequest(
        @Min(0) Integer tokenBalance,
        @Min(0) @Max(100) Integer tokenLowThresholdPct,
        @Min(0) Integer tokenMonthlyGrantRef
) {}
