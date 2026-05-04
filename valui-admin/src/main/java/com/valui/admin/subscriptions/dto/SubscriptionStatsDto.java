package com.valui.admin.subscriptions.dto;

import com.valui.user.dto.PlanStatsDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Статистика подписок по планам")
public record SubscriptionStatsDto(
        @Schema(description = "Суммарное количество активных подписок") long totalActive,
        @Schema(description = "Истекают в ближайшие 24 часа") int expiringIn24h,
        @Schema(description = "Разбивка по планам") List<PlanStatsDto> byPlan
) {}
