package com.valui.monitor.scheduler;

import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ParsedItem;
import com.valui.monitor.scheduler.ControllerTaskExecutor.TaskContext;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual Thread task that polls one controller per iteration.
 * Not a Spring bean — instantiated and held by MonitorScheduler.
 *
 * Concurrency guards:
 *  - globalSemaphore: system-wide cap (monitor.max-concurrent-tasks)
 *  - perUserCounter: per-user cap (monitor.max-tasks-per-user)
 *
 * If either guard rejects, the iteration is skipped silently; the next
 * scheduled tick will retry.
 */
@Slf4j
public class ControllerTask implements Runnable {

    private final UUID controllerId;
    private final UUID userId;
    private final ControllerTaskExecutor executor;
    private final Semaphore globalSemaphore;
    private final ConcurrentHashMap<UUID, AtomicInteger> perUserCounter;
    private final int maxTasksPerUser;
    private final MonitorMetrics metrics;
    private final PollHistoryService pollHistory;

    ControllerTask(UUID controllerId,
                   UUID userId,
                   ControllerTaskExecutor executor,
                   Semaphore globalSemaphore,
                   ConcurrentHashMap<UUID, AtomicInteger> perUserCounter,
                   int maxTasksPerUser,
                   MonitorMetrics metrics,
                   PollHistoryService pollHistory) {
        this.controllerId   = controllerId;
        this.userId         = userId;
        this.executor       = executor;
        this.globalSemaphore = globalSemaphore;
        this.perUserCounter = perUserCounter;
        this.maxTasksPerUser = maxTasksPerUser;
        this.metrics        = metrics;
        this.pollHistory    = pollHistory;
    }

    @Override
    public void run() {
        // ── Global concurrency guard ──────────────────────────────────────────
        if (!globalSemaphore.tryAcquire()) {
            metrics.onTaskSkipped();
            log.debug("⏸  Глобальный лимит задач достигнут — контроллер {} пропущен", controllerId);
            return;
        }
        Timer.Sample sample = Timer.start();
        try {
            // ── Per-user concurrency guard ────────────────────────────────────
            AtomicInteger userSlots = perUserCounter.computeIfAbsent(userId, k -> new AtomicInteger(0));
            int current = userSlots.incrementAndGet();
            if (current > maxTasksPerUser) {
                userSlots.decrementAndGet();
                metrics.onTaskSkipped();
                log.debug("⏸  Лимит пользователя userId={} достигнут — контроллер {} пропущен", userId, controllerId);
                return;
            }
            try {
                executeTask();
            } finally {
                userSlots.decrementAndGet();
            }
        } finally {
            globalSemaphore.release();
            sample.stop(metrics.taskTimer());
        }
    }

    private void executeTask() {
        // TX 1: load fresh controller context
        Optional<TaskContext> ctxOpt = executor.loadContext(controllerId);
        if (ctxOpt.isEmpty()) {
            log.debug("⏭  Контроллер {} неактивен или URL не распознан — пропуск", controllerId);
            return;
        }
        TaskContext ctx = ctxOpt.get();

        if (!executor.isParserAvailable(ctx.bookmaker())) {
            log.debug("⏭  Parser {} not available — controller {} skipped", ctx.bookmaker(), controllerId);
            return;
        }

        // ── Poll starts here — from this point we record history ─────────────
        Instant startedAt = Instant.now();
        long startNs = System.nanoTime();

        // External HTTP call (outside any transaction)
        List<ParsedItem> fetched;
        try {
            fetched = executor.fetch(ctx);
        } catch (Exception e) {
            log.warn("⚠️  Ошибка парсера для контроллера {} ({}): {}", controllerId, ctx.bookmaker(), e.getMessage());
            pollHistory.record(controllerId, startedAt, msElapsed(startNs), -1, "error");
            return;
        }

        // TX 2: dedup, persist, update timestamps, publish domain events
        int eventsFound = 0;
        String status = "ok";
        try {
            eventsFound = executor.persistNewEvents(ctx, fetched);
            if (eventsFound > 0) {
                metrics.onEventsDetected(eventsFound);
                log.debug("🔔 Контроллер {}: {} новых событий обнаружено", controllerId, eventsFound);
            }
        } catch (Exception e) {
            log.error("❌ Ошибка сохранения событий для контроллера {}: {}", controllerId, e.getMessage(), e);
            eventsFound = -1;
            status = "error";
        }
        pollHistory.record(controllerId, startedAt, msElapsed(startNs), eventsFound, status);
    }

    private static long msElapsed(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
