package com.valui.admin.parsers.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Результат тестового парсинга")
public record TestParseResult(
        @Schema(description = "Успешно ли выполнен парсинг") boolean success,
        @Schema(description = "Количество найденных событий") int eventCount,
        @Schema(description = "Первые 5 событий (для preview)") List<String> sample,
        @Schema(description = "Сообщение об ошибке при неудаче") String errorMessage,
        @Schema(description = "Время выполнения запроса, мс") long latencyMs
) {}
