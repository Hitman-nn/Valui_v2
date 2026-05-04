package com.valui.admin.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Поля контроллера для обновления (null-поля игнорируются)")
public record UpdateControllerRequest(

        @Schema(description = "Новое название контроллера", example = "АПЛ — топ матчи", nullable = true)
        @Size(max = 200, message = "title не может быть длиннее 200 символов")
        String title,

        @Schema(description = "Новый regex-фильтр событий. Пустая строка — удалить фильтр.",
                example = "Арсенал|Ливерпуль", nullable = true)
        @Size(max = 500, message = "filterRule не может быть длиннее 500 символов")
        String filterRule
) {}
