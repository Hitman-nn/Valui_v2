package com.valui.monitor.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class MonitorMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger scheduledCount;
    private final AtomicInteger queueDepth;
    private final Counter eventsDetected;
    private final Counter tasksSkipped;
    private final Counter tasksDeferred;
    private final Timer taskTimer;
    private final DistributionSummary dispatchLag;
    private final Counter dedupHit;
    private final Counter dedupMiss;
    private final ConcurrentHashMap<UUID, AtomicInteger> dedupSetSizes = new ConcurrentHashMap<>();

    public MonitorMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.scheduledCount = registry.gauge("monitor.controllers.scheduled", new AtomicInteger(0));

        this.queueDepth = registry.gauge("monitor.scheduler.queue.depth",
                new AtomicInteger(0));

        this.eventsDetected = Counter.builder("monitor.events.detected")
                .description("Cumulative count of new sport events detected")
                .register(registry);

        this.tasksSkipped = Counter.builder("monitor.tasks.skipped")
                .description("Tasks skipped due to concurrency limits (legacy, should stay zero with DRR)")
                .register(registry);

        this.tasksDeferred = Counter.builder("monitor.tasks.deferred")
                .description("Tasks deferred (re-queued) because global pool was full — throttle not drop")
                .register(registry);

        this.taskTimer = Timer.builder("monitor.task.duration")
                .description("End-to-end duration of one controller task")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.dispatchLag = DistributionSummary.builder("monitor.scheduler.dispatch.lag.ms")
                .description("Lag between scheduled run time and actual dispatch (ms)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.dedupHit = Counter.builder("cache.dedup.hit")
                .description("Events skipped as already seen (Redis dedup)")
                .register(registry);

        this.dedupMiss = Counter.builder("cache.dedup.miss")
                .description("New events not seen before (Redis dedup miss)")
                .register(registry);
    }

    public void onControllerScheduled()   { scheduledCount.incrementAndGet(); }
    public void onControllerUnscheduled() { scheduledCount.decrementAndGet(); }
    public void onEventsDetected(int n)   { eventsDetected.increment(n); }
    public void onTaskSkipped()           { tasksSkipped.increment(); }
    public void onTaskDeferred()          { tasksDeferred.increment(); }
    public void onDispatchLag(long ms)    { dispatchLag.record(ms); }
    public void updateQueueDepth(int n)   { if (queueDepth != null) queueDepth.set(n); }
    public void onDedupHit()              { dedupHit.increment(); }
    public void onDedupMiss()             { dedupMiss.increment(); }
    public Timer taskTimer()              { return taskTimer; }

    /**
     * Registers a gauge that reports the maximum seconds since any controller last started.
     * Called once by {@link com.valui.monitor.scheduler.MonitorScheduler} after init.
     */
    public void registerStarvationGauge(Supplier<Number> supplier) {
        Gauge.builder("monitor.scheduler.starvation.seconds", supplier, s -> s.get().doubleValue())
                .description("Max seconds since any controller last started a poll")
                .register(registry);
    }

    /** Updates (and lazily registers) the per-controller dedup set-size gauge. */
    public void updateDedupSetSize(UUID controllerId, int size) {
        dedupSetSizes.computeIfAbsent(controllerId, id -> {
            AtomicInteger gauge = new AtomicInteger(0);
            Gauge.builder("cache.dedup.set_size", gauge, AtomicInteger::get)
                    .description("Current size of the Redis dedup SET for this controller")
                    .tag("controller_id", id.toString())
                    .register(registry);
            return gauge;
        }).set(size);
    }
}
