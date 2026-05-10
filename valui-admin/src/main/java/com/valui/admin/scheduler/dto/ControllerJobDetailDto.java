package com.valui.admin.scheduler.dto;

import com.valui.monitor.history.PollHistoryEntry;

import java.util.List;

public record ControllerJobDetailDto(
        ControllerJobDto       job,
        List<PollHistoryEntry> pollHistory
) {}
