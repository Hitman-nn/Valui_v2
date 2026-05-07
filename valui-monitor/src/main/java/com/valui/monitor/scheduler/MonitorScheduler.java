package com.valui.monitor.scheduler;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.event.SubscriptionChangedEvent;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ControllerScheduleInfo;
import com.valui.user.api.ControllerPortService;
import com.valui.user.event.ControllerResumedEvent;
import com.valui.user.event.ControllerSuspendedEvent;
import com.valui.user.event.SubscriptionExpiredEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ControllerPortService controllerPort;

    private final ScheduledExecutorService triggerPool;
    private final ExecutorService taskPool;
    private final Semaphore globalSemaphore;
    private final ConcurrentHashMap<UUID, AtomicInteger> perUserCounter = new ConcurrentHashMap<>();

    /** controllerId → scheduled trigger future. */
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    /** Production constructor — creates real virtual-thread pools. */
    @Autowired
    public MonitorScheduler(ControllerTaskExecutor taskExecutor,
                            MonitorProperties props,
                            MonitorMetrics metrics,
                            EventDeduplicationService dedup,
                            ControllerPortService controllerPort) {
        this(taskExecutor, props, metrics, dedup, controllerPort,
                Executors.newScheduledThreadPool(
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                        Thread.ofPlatform().name("monitor-trigger-", 0).factory()),
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("monitor-task-", 0).factory()));
    }

    /** Package-private constructor for tests — allows injecting stub executors. */
    MonitorScheduler(ControllerTaskExecutor taskExecutor,
                     MonitorProperties props,
                     MonitorMetrics metrics,
                     EventDeduplicationService dedup,
                     ControllerPortService controllerPort,
                     ScheduledExecutorService triggerPool,
                     ExecutorService taskPool) {
        this.taskExecutor    = taskExecutor;
        this.props           = props;
        this.metrics         = metrics;
        this.dedup           = dedup;
        this.controllerPort  = controllerPort;
        this.triggerPool     = triggerPool;
        this.taskPool        = taskPool;
        this.globalSemaphore = new Semaphore(props.getMaxConcurrentTasks());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        List<ControllerScheduleInfo> controllers = taskExecutor.loadAllActiveForScheduling();
        controllers.stream()
                .filter(info -> controllerPort.hasActiveSubscriptions(info.controllerId()))
                .forEach(info -> doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec()));
        log.info("Монитор запущен: {} контроллеров поставлено в очередь", controllers.size());
    }

    @PreDestroy
    void shutdown() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        triggerPool.shutdownNow();
        taskPool.shutdownNow();
        log.info("🛑 Монитор остановлен. Все задачи отменены.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void scheduleController(UUID controllerId, UUID userId, int pollIntervalSec) {
        // computeIfAbsent is atomic on ConcurrentHashMap: exactly one thread will build
        // the future for a given key; concurrent callers for the same id are no-ops.
        scheduled.computeIfAbsent(controllerId,
                id -> buildFuture(id, userId, pollIntervalSec));
    }

    public void unscheduleController(UUID controllerId) {
        ScheduledFuture<?> future = scheduled.remove(controllerId);
        if (future != null) {
            future.cancel(false);
            metrics.onControllerUnscheduled();
            log.debug("⏹  Контроллер {} снят с расписания", controllerId);
        }
    }

    /**
     * Cancels all futures, reloads from DB, and reschedules.
     * Meant for admin actions or bulk plan changes.
     */
    public synchronized void rescheduleAll() {
        log.info("🔄 Перезапуск расписания: отменяем {} задач", scheduled.size());
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();

        List<ControllerScheduleInfo> all = taskExecutor.loadAllActiveForScheduling();
        all.forEach(info -> doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec()));
        log.info("🔄 Перезапуск расписания завершён: {} задач запланировано", all.size());
    }

    public Set<UUID> getScheduledControllerIds() {
        return Set.copyOf(scheduled.keySet());
    }

    // ── ApplicationEvent listeners ────────────────────────────────────────────

    @EventListener
    public void on(ControllerAddedEvent e) {
        log.debug("▶  Добавлен контроллер {}: ставим в расписание", e.controllerId());
        scheduleController(e.controllerId(), e.userId(), e.pollIntervalSec());
    }

    @EventListener
    public void on(ControllerRemovedEvent e) {
        log.debug("◼  Удалён контроллер {}: снимаем с расписания", e.controllerId());
        unscheduleController(e.controllerId());
    }

    @EventListener
    public void on(SubscriptionChangedEvent e) {
        log.info("🔄 Подписка изменена: перепланируем контроллеры userId={}", e.userId());
        rescheduleUser(e.userId());
    }

    @EventListener
    public void on(SubscriptionExpiredEvent e) {
        log.info("Подписка истекла: перепланируем контроллеры userId={}", e.userId());
        rescheduleUser(e.userId());
    }

    @EventListener
    public void on(ControllerSuspendedEvent e) {
        dedup.clearController(e.controllerId());
        controllerPort.updateLastCheckedAt(e.controllerId(), null);
        unscheduleController(e.controllerId());
        log.info("Контроллер {} приостановлен (токены)", e.controllerId());
    }

    @EventListener
    public void on(ControllerResumedEvent e) {
        if (!scheduled.containsKey(e.controllerId())) {
            doSchedule(e.controllerId(), e.userId(), e.pollIntervalSec());
            log.info("Контроллер {} возобновлён (токены)", e.controllerId());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Creates and registers a scheduled future. Must only be called when the key
     * is NOT already in {@code scheduled} (used by init, rescheduleAll, rescheduleUser).
     */
    private void doSchedule(UUID controllerId, UUID userId, int pollIntervalSec) {
        scheduled.put(controllerId, buildFuture(controllerId, userId, pollIntervalSec));
    }

    /**
     * Builds and starts a {@link ScheduledFuture} without touching the {@code scheduled} map.
     * Safe to call inside {@code computeIfAbsent} because it does not modify the map.
     */
    private ScheduledFuture<?> buildFuture(UUID controllerId, UUID userId, int pollIntervalSec) {
        try {
            dedup.seedIfAbsent(controllerId);
        } catch (Exception e) {
            log.warn("⚠️  Инициализация дедупликации для контроллера {} не удалась (продолжаем): {}", controllerId, e.getMessage());
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

        metrics.onControllerScheduled();
        log.debug("▶  Контроллер {} запланирован каждые {}с", controllerId, pollIntervalSec);
        return future;
    }

    /**
     * Synchronized to prevent a race with {@link #rescheduleAll()}: both methods
     * read-then-modify the {@code scheduled} map as a compound operation.
     */
    private synchronized void rescheduleUser(UUID userId) {
        List<ControllerScheduleInfo> userControllers = taskExecutor.loadActiveForUser(userId);
        userControllers.forEach(info -> {
            unscheduleController(info.controllerId());
            doSchedule(info.controllerId(), info.userId(), info.pollIntervalSec());
        });
    }
}
