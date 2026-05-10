package com.valui.admin.scheduler.dto;

public record SchedulerConfigUpdateRequest(
        Integer maxConcurrentTasks,
        Integer defaultPollIntervalSec,
        Integer fetchBudgetMs,
        Integer deferBaseMs,
        Integer deferJitterMs,
        Integer defaultUserWeight
) {}
