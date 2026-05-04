package com.valui.admin.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Обзорные метрики системы")
public record SystemOverviewDto(
        @Schema(description = "Всего пользователей") long totalUsers,
        @Schema(description = "Активных пользователей") long activeUsers,
        @Schema(description = "Активных контроллеров") long activeControllers,
        @Schema(description = "Событий за сегодня") long eventsToday,
        @Schema(description = "Уведомлений за сегодня") long notificationsToday
) {}
