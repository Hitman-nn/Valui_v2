package com.valui.user.dto;

public record PlanStatsDto(
        String planCode,
        String planName,
        long activeCount
) {}
