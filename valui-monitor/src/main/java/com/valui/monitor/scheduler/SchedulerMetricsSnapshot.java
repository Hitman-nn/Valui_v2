package com.valui.monitor.scheduler;

public record SchedulerMetricsSnapshot(
        long   ts,             // epoch millis
        int    queueDepth,
        int    inFlight,       // scheduled - available slots
        int    availableSlots,
        double lagP95Ms,
        double taskDurP95Ms,
        long   deferredTotal,
        long   eventsTotal
) {}
