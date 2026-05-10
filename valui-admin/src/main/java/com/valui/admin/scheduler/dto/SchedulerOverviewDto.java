package com.valui.admin.scheduler.dto;

import java.util.List;

public record SchedulerOverviewDto(
        SchedulerConfigDto     config,
        SchedulerStatsDto      stats,
        List<ControllerJobDto> jobs
) {}
