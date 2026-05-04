package com.valui.admin.monitoring.dto;

import com.valui.admin.monitoring.validation.ValidBookmakerUrl;
import com.valui.common.domain.ControllerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Запрос на создание контроллера мониторинга")
public record CreateControllerApiRequest(

        @Schema(description = "URL страницы букмекера (турнир или вид спорта)",
                example = "https://1xbet.kz/line/football/123456")
        @NotBlank(message = "url обязателен")
        @Size(max = 2048, message = "url не может быть длиннее 2048 символов")
        @ValidBookmakerUrl
        String url,

        @Schema(description = "Код букмекера (определяется автоматически из URL, если не указан)",
                example = "XBET", nullable = true)
        String bookmaker,

        @Schema(description = "Название контроллера для отображения",
                example = "Лига чемпионов", nullable = true)
        @Size(max = 200, message = "title не может быть длиннее 200 символов")
        String title,

        @Schema(description = "Регулярное выражение для фильтрации событий",
                example = "Реал|Барселона", nullable = true)
        @Size(max = 500, message = "filterRule не может быть длиннее 500 символов")
        String filterRule,

        @Schema(description = "Создать контроллер в режиме тишины (без уведомлений)",
                defaultValue = "false")
        boolean muted,

        @Schema(description = "Явное указание типа (null = автоопределение)",
                nullable = true)
        ControllerType typeHint,

        @Schema(description = "Chat ID для доставки уведомлений. " +
                "Null = личный чат пользователя (telegramId из токена).",
                example = "-1001234567890", nullable = true)
        Long chatId
) {}
