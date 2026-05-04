package com.valui.admin.subscriptions.dto;

import jakarta.validation.constraints.NotBlank;

public record GrantPlanRequest(
        @NotBlank String planCode
) {}
