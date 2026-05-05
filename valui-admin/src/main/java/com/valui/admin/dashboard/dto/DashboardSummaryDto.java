package com.valui.admin.dashboard.dto;

import java.util.List;

public record DashboardSummaryDto(
        UserStats users,
        ControllerStats controllers,
        PeriodStats events,
        PeriodStats notifications,
        SubStats subscriptions
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

    public record SubStats(
            List<PlanCount> byPlan,
            long expiringIn7d
    ) {}

    public record PlanCount(String planCode, String planName, long count) {}
}
