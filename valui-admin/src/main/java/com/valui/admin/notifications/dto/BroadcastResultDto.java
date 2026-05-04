package com.valui.admin.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Результат запуска рассылки")
public record BroadcastResultDto(
        @Schema(description = "Сколько пользователей получат сообщение") int recipientCount,
        @Schema(description = "Фильтр по плану, применённый при рассылке") String planFilter
) {}
