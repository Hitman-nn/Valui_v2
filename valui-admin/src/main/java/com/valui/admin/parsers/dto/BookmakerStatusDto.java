package com.valui.admin.parsers.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Статус парсера букмекера")
public record BookmakerStatusDto(
        @Schema(description = "Код букмекера", example = "XBET") String bookmaker,
        @Schema(description = "Состояние circuit breaker", example = "CLOSED") String cbState,
        @Schema(description = "Цвет индикатора: green/yellow/red", example = "green") String indicator,
        @Schema(description = "Процент успешных вызовов (0-100)", example = "98.5") float successRate,
        @Schema(description = "Количество успешных вызовов") long successfulCalls,
        @Schema(description = "Количество неудачных вызовов") long failedCalls,
        @Schema(description = "Заблокировано circuit breaker'ом") long notPermittedCalls
) {}
