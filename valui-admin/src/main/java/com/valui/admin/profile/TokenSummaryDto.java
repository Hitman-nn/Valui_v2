package com.valui.admin.profile;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Информация о балансе токенов пользователя")
public record TokenSummaryDto(

        @Schema(description = "Текущий баланс токенов", example = "150")
        int balance,

        @Schema(description = "Ежемесячный грант токенов по плану", example = "200")
        int monthlyGrant,

        @Schema(description = "Порог низкого баланса в процентах от ежемесячного гранта", example = "20")
        int lowThresholdPct
) {}
