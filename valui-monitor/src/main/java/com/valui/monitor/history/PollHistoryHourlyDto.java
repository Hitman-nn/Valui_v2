package com.valui.monitor.history;

import java.time.Instant;

public record PollHistoryHourlyDto(
        Instant hour,
        long    totalPolls,
        long    avgDurationMs,
        long    okCount,
        long    errorCount,
        long    totalEvents
) {}
