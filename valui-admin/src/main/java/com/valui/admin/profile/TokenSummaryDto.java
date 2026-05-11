package com.valui.admin.profile;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Информация о балансе токенов пользователя")
public record TokenSummaryDto(

        @Schema(description = "Текущий баланс токенов", example = "150")
        int balance,

        @Schema(description = "Ежемесячный грант токенов по плану", example = "200")
        int monthlyGrant,

        @Schema(description = "Последний уведомлённый абсолютный порог (100/50/10 токенов), null = не уведомлялся", example = "50")
        Integer lowThreshold
) {}
