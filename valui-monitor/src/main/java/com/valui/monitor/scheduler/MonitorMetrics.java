package com.valui.monitor.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MonitorMetrics {

    private final AtomicInteger scheduledCount;
    private final Counter eventsDetected;
    private final Counter tasksSkipped;
    private final Timer taskTimer;

    public MonitorMetrics(MeterRegistry registry) {
        this.scheduledCount = registry.gauge(
                "monitor.controllers.scheduled",
                new AtomicInteger(0));

        this.eventsDetected = Counter.builder("monitor.events.detected")
                .description("Cumulative count of new sport events detected")
                .register(registry);

        this.tasksSkipped = Counter.builder("monitor.tasks.skipped")
                .description("Tasks skipped due to concurrency limits")
                .register(registry);

        this.taskTimer = Timer.builder("monitor.task.duration")
                .description("End-to-end duration of one controller task")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void onControllerScheduled()   { scheduledCount.incrementAndGet(); }
    public void onControllerUnscheduled() { scheduledCount.decrementAndGet(); }
    public void onEventsDetected(int n)   { eventsDetected.increment(n); }
    public void onTaskSkipped()           { tasksSkipped.increment(); }
    public Timer taskTimer()              { return taskTimer; }
}
