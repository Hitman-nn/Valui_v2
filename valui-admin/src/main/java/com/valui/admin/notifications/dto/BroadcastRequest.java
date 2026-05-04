package com.valui.admin.notifications.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Запрос на массовую рассылку")
public record BroadcastRequest(
        @NotBlank @Size(max = 4096)
        @Schema(description = "Текст сообщения (Markdown)") String text,

        @Schema(description = "Фильтр по плану: ALL/FREE/PRO/PREMIUM (null = ALL)", example = "FREE")
        String planCode
) {}
