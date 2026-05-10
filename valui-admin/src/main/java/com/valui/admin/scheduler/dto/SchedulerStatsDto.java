package com.valui.admin.scheduler.dto;

public record SchedulerStatsDto(
        int    scheduledJobs,
        int    queueDepth,
        int    availableSlots,
        int    maxSlots,
        double starvationSec,
        long   tasksDeferred,
        long   tasksSkipped,
        long   eventsDetected,
        double lagP50Ms,
        double lagP95Ms,
        double lagP99Ms,
        double taskDurP50Ms,
        double taskDurP95Ms,
        double taskDurP99Ms
) {}
