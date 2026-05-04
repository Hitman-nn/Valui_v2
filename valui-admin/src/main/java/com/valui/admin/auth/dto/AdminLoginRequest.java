package com.valui.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AdminLoginRequest(
    @NotNull @Positive Long telegramId,
    @NotBlank String adminPassword
) {}
