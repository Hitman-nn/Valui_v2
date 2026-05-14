package com.valui.monitor.scheduler;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ControllerScheduleInfo;
import com.valui.monitor.scheduler.drr.DrrDispatcher;
import com.valui.monitor.scheduler.SchedulerConfigStore;
import com.valui.monitor.scheduler.job.ControllerJob;
import com.valui.monitor.scheduler.job.JobRegistry;
import com.valui.monitor.scheduler.state.SchedulerStateStore;
import com.valui.user.api.ControllerPortService;
import com.valui.user.event.ControllerResumedEvent;
import com.valui.user.event.ControllerSuspendedEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Lifecycle coordinator for the controller monitoring scheduler.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>On startup: load all active controllers, restore per-controller {@code nextRunAt}
 *       from Redis (crash recovery), enqueue them into {@link DrrDispatcher}.
 *   <li>Spring event listeners: translate domain events into registry + dispatcher calls.
 *   <li>Public API: {@link #scheduleController}, {@link #unscheduleController},
 *       {@link #rescheduleAll}, {@link #getScheduledControllerIds}.
 * </ul>
 *
 * <p>Dispatch, fairness, and backpressure are handled entirely by {@link DrrDispatcher}.
 */
@Slf4j
@Component
public class MonitorScheduler {

    private final ControllerTaskExecutor    taskExecutor;
    private final MonitorProperties         props;
    private final MonitorMetrics            metrics;
    private final EventDeduplicationService dedup;
    private final ControllerPortService     controllerPort;
    private final JobRegistry               jobRegistry;
    private final DrrDispatcher             dispatcher;
    private final SchedulerStateStore       stateStore;
    // Declared as dependency so Spring calls its @PostConstruct (loadFromDb) before our init().
    @SuppressWarnings("unused")
    private final SchedulerConfigStore      configStore;

    /** Production constructor — all dependencies via Spring. */
    @Autowired
    public MonitorScheduler(ControllerTaskExecutor taskExecutor,
                            MonitorProperties props,
                            MonitorMetrics metrics,
                            EventDeduplicationService dedup,
                            ControllerPortService controllerPort,
                            JobRegistry jobRegistry,
                            DrrDispatcher dispatcher,
                            SchedulerStateStore stateStore,
                            SchedulerConfigStore configStore) {
        this.taskExecutor   = taskExecutor;
        this.props          = props;
        this.metrics        = metrics;
        this.dedup          = dedup;
        this.controllerPort = controllerPort;
        this.jobRegistry    = jobRegistry;
        this.dispatcher     = dispatcher;
        this.stateStore     = stateStore;
        this.configStore    = configStore;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        dispatcher.start();

        List<ControllerScheduleInfo> controllers = taskExecutor.loadAllActiveForScheduling();
        Map<com.valui.common.domain.BookmakerType, Long> byBk = new LinkedHashMap<>();

        for (ControllerScheduleInfo info : controllers) {
            if (!controllerPort.hasActiveSubscriptions(info.controllerId())) continue;

            seedDedup(info.controllerId());

            // Recovery: restore nextRunAt from Redis. If persisted time is in the past or absent,
            // use a random startup jitter spread over [0, pollInterval) seconds to avoid a thundering herd.
            Instant nextRunAt = stateStore.loadNextRunAt(info.controllerId())
                    .filter(t -> t.isAfter(Instant.now()))
                    .orElseGet(() -> Instant.now().plusSeconds(
                            ThreadLocalRandom.current().nextInt(info.pollIntervalSec())));

            stateStore.clearInFlight(info.controllerId()); // discard any crashed inFlight flag

            ControllerJob job = ControllerJob.initial(
                    info.controllerId(), info.userId(), info.pollIntervalSec(), nextRunAt);
            jobRegistry.put(job);
            dispatcher.enqueue(job);
            byBk.merge(info.bookmaker(), 1L, Long::sum);
        }

        // Register starvation gauge (max seconds since any controller last ran).
        metrics.registerStarvationGauge(() -> {
            Instant now = Instant.now();
            return jobRegistry.all().stream()
                    .filter(j -> j.lastStartedAt() != null)
                    .mapToLong(j -> java.time.Duration.between(j.lastStartedAt(), now).toSeconds())
                    .max()
                    .orElse(0L);
        });

        long scheduled = byBk.values().stream().mapToLong(Long::longValue).sum();
        String breakdown = byBk.entrySet().stream()
                .sorted(Map.Entry.<com.valui.common.domain.BookmakerType, Long>comparingByValue().reversed())
                .map(e -> e.getKey().name() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
        log.info("Монитор запущен: {} контроллеров в очереди ({})",
                scheduled, breakdown.isEmpty() ? "нет активных" : breakdown);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void scheduleController(UUID controllerId, UUID userId, int pollIntervalSec) {
        if (jobRegistry.contains(controllerId)) return; // idempotent

        seedDedup(controllerId);
        ControllerJob job = ControllerJob.initial(controllerId, userId, pollIntervalSec, Instant.now());
        jobRegistry.put(job);
        dispatcher.enqueue(job);
        metrics.onControllerScheduled();
        log.debug("▶  Контроллер {} запланирован каждые {}с", controllerId, pollIntervalSec);
    }

    public void unscheduleController(UUID controllerId) {
        if (!jobRegistry.contains(controllerId)) return;
        dispatcher.cancel(controllerId);
        jobRegistry.remove(controllerId);
        metrics.onControllerUnscheduled();
        log.debug("⏹  Контроллер {} снят с расписания", controllerId);
    }

    /** Cancels all jobs, reloads from DB, and re-enqueues. For admin actions / bulk plan changes. */
    public synchronized void rescheduleAll() {
        log.info("🔄 Перезапуск расписания: отменяем {} задач", jobRegistry.size());
        Set<UUID> ids = Set.copyOf(jobRegistry.controllerIds());
        ids.forEach(id -> {
            dispatcher.cancel(id);
            jobRegistry.remove(id);
        });

        List<ControllerScheduleInfo> all = taskExecutor.loadAllActiveForScheduling();
        all.forEach(info -> {
            ControllerJob job = ControllerJob.initial(
                    info.controllerId(), info.userId(), info.pollIntervalSec(), Instant.now());
            jobRegistry.put(job);
            dispatcher.enqueue(job);
        });
        log.info("🔄 Перезапуск расписания завершён: {} задач запланировано", all.size());
    }

    public Set<UUID> getScheduledControllerIds() {
        return jobRegistry.controllerIds();
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
    public void on(ControllerSuspendedEvent e) {
        dedup.clearController(e.controllerId());
        controllerPort.updateLastCheckedAt(e.controllerId(), null);
        unscheduleController(e.controllerId());
        log.info("Контроллер {} приостановлен (токены)", e.controllerId());
    }

    @EventListener
    public void on(ControllerResumedEvent e) {
        if (!jobRegistry.contains(e.controllerId())) {
            scheduleController(e.controllerId(), e.userId(), e.pollIntervalSec());
            log.info("Контроллер {} возобновлён (токены)", e.controllerId());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedDedup(UUID controllerId) {
        try {
            dedup.seedIfAbsent(controllerId);
        } catch (Exception e) {
            log.warn("⚠️  Инициализация дедупликации для {} не удалась (продолжаем): {}", controllerId, e.getMessage());
        }
    }
}
