package com.valui.monitor.scheduler;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.event.SubscriptionChangedEvent;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ControllerScheduleInfo;
import com.valui.user.event.SubscriptionExpiredEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central scheduler for all active controller monitoring tasks.
 *
 * Architecture:
 *  - triggerPool: small platform-thread pool that fires tasks on schedule
 *  - taskPool:    virtual-thread-per-task executor that actually runs task bodies
 *  - globalSemaphore + perUserCounter: concurrency limits
 *
 * Lifecycle:
 *  - @PostConstruct: loads all active controllers from DB, schedules them
 *  - ControllerAddedEvent → scheduleController
 *  - ControllerRemovedEvent → unscheduleController
 *  - SubscriptionChangedEvent / SubscriptionExpiredEvent → reschedule user's controllers
 *  - @PreDestroy: cancels all futures, shuts down pools
 */
@Slf4j
@Component
public class MonitorScheduler {

    private final ControllerTaskExecutor taskExecutor;
    private final MonitorProperties props;
    private final MonitorMetrics metrics;
    private final EventDeduplicationService dedup;

    private final ScheduledExecutorService triggerPool;
    private final ExecutorService taskPool;
    private final Semaphore globalSemaphore;
    private final ConcurrentHashMap<UUID, AtomicInteger> perUserCounter = new ConcurrentHashMap<>();

    /** controllerId → scheduled trigger future. */
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /** Production constructor — creates real virtual-thread pools. */
    public MonitorScheduler(ControllerTaskExecutor taskExecutor,
                            MonitorProperties props,
                            MonitorMetrics metrics,
                            EventDeduplicationService dedup) {
        this(taskExecutor, props, metrics, dedup,
                Executors.newScheduledThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                        Thread.ofPlatform().name("monitor-trigger-", 0).factory()),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Package-private constructor for tests — allows injecting stub executors. */
    MonitorScheduler(ControllerTaskExecutor taskExecutor,
                     MonitorProperties props,
                     MonitorMetrics metrics,
                     EventDeduplicationService dedup,
                     ScheduledExecutorService triggerPool,
                     ExecutorService taskPool) {
        this.taskExecutor    = taskExecutor;
        this.props           = props;
        this.metrics         = metrics;
        this.dedup           = dedup;
        this.triggerPool     = triggerPool;
        this.taskPool        = taskPool;
        this.globalSemaphore = new Semaphore(props.getMaxConcurrentTasks());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        List<ControllerScheduleInfo> controllers = taskExecutor.loadAllActiveForScheduling();
        controllers.forEach(info ->
                doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec()));
        log.info("MonitorScheduler started: {} controllers scheduled", controllers.size());
    }

    @PreDestroy
    void shutdown() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        triggerPool.shutdownNow();
        taskPool.shutdownNow();
        log.info("MonitorScheduler stopped.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void scheduleController(UUID controllerId, UUID userId, int pollIntervalSec) {
        if (scheduled.containsKey(controllerId)) {
            log.debug("Controller {} already scheduled — skipping duplicate", controllerId);
            return;
        }
        doSchedule(controllerId, userId, pollIntervalSec);
    }

    public void unscheduleController(UUID controllerId) {
        ScheduledFuture<?> future = scheduled.remove(controllerId);
        if (future != null) {
            future.cancel(false);
            metrics.onControllerUnscheduled();
            log.debug("Controller {} unscheduled", controllerId);
        }
    }

    /**
     * Cancels all futures, reloads from DB, and reschedules.
     * Meant for admin actions or bulk plan changes.
     */
    public synchronized void rescheduleAll() {
        log.info("rescheduleAll: cancelling {} scheduled tasks", scheduled.size());
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();

        List<ControllerScheduleInfo> all = taskExecutor.loadAllActiveForScheduling();
        all.forEach(info -> doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec()));
        log.info("rescheduleAll: {} tasks rescheduled", all.size());
    }

    public Set<UUID> getScheduledControllerIds() {
        return Set.copyOf(scheduled.keySet());
    }

    // ── ApplicationEvent listeners ────────────────────────────────────────────

    @EventListener
    public void on(ControllerAddedEvent e) {
        log.debug("ControllerAddedEvent: scheduling controller {}", e.controllerId());
        scheduleController(e.controllerId(), e.userId(), e.pollIntervalSec());
    }

    @EventListener
    public void on(ControllerRemovedEvent e) {
        log.debug("ControllerRemovedEvent: unscheduling controller {}", e.controllerId());
        unscheduleController(e.controllerId());
    }

    @EventListener
    public void on(SubscriptionChangedEvent e) {
        log.info("SubscriptionChangedEvent: rescheduling controllers for userId={}", e.userId());
        rescheduleUser(e.userId());
    }

    @EventListener
    public void on(SubscriptionExpiredEvent e) {
        log.info("SubscriptionExpiredEvent: rescheduling controllers for userId={}", e.userId());
        rescheduleUser(e.userId());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void doSchedule(UUID controllerId, UUID userId, int pollIntervalSec) {
        // Seed Redis dedup SET from DB (only if the key doesn't exist yet)
        try {
            dedup.seedIfAbsent(controllerId);
        } catch (Exception e) {
            log.warn("Dedup seed failed for controller {} — will proceed without pre-seeding: {}", controllerId, e.getMessage());
        }

        ControllerTask task = new ControllerTask(
                controllerId, userId, taskExecutor,
                globalSemaphore, perUserCounter,
                props.getMaxTasksPerUser(), metrics);

        ScheduledFuture<?> future = triggerPool.scheduleWithFixedDelay(
                () -> taskPool.submit(task),
                0,
                pollIntervalSec,
                TimeUnit.SECONDS);

        scheduled.put(controllerId, future);
        metrics.onControllerScheduled();
        log.debug("Scheduled controller {} every {}s", controllerId, pollIntervalSec);
    }

    private void rescheduleUser(UUID userId) {
        List<ControllerScheduleInfo> userControllers = taskExecutor.loadActiveForUser(userId);
        userControllers.forEach(info -> {
            unscheduleController(info.controllerId());
            doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec());
        });
    }
}
