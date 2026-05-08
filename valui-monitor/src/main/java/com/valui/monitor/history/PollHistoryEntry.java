package com.valui.monitor.history;

import java.time.Instant;

public record PollHistoryEntry(
        Instant startedAt,
        long    durationMs,
        int     eventsFound,  // -1 = error
        String  status        // "ok" | "error"
) {}
