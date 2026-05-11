package com.valui.admin.dashboard.dto;

public record DashboardSummaryDto(
        UserStats users,
        ControllerStats controllers,
        PeriodStats events,
        PeriodStats notifications
) {
    public record UserStats(
            long total,
            long active,
            long banned,
            long newToday,
            long newThisWeek
    ) {}

    public record ControllerStats(
            long total,
            long active,
            long muted,
            long stale
    ) {}

    public record PeriodStats(
            long today,
            long thisWeek,
            long thisMonth,
            long thisYear,
            long total
    ) {}
}
