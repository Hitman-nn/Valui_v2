package com.valui.admin.scheduler.dto;

public record SchedulerConfigDto(
        int maxConcurrentTasks,
        int defaultPollIntervalSec,
        int fetchBudgetMs,
        int deferBaseMs,
        int deferJitterMs,
        int defaultUserWeight
) {}
