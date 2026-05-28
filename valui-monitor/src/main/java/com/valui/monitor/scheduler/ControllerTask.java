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
import java.util.concurrent.*;

/**
 * Virtual-thread task that polls one controller per execution.
 * Not a Spring bean — instantiated by {@link com.valui.monitor.scheduler.drr.DrrDispatcher}.
 *
 * <p>Concurrency guards (global semaphore, per-user counter) were removed and are now
 * the responsibility of the dispatcher. This class only performs the actual poll work:
 * load context → fetch (with budget timeout) → persist.
 */
@Slf4j
public class ControllerTask implements Runnable {

    private final UUID                   controllerId;
    private final UUID                   userId;
    private final ControllerTaskExecutor executor;
    private final MonitorMetrics         metrics;
    private final PollHistoryService     pollHistory;
    private final int                    fetchBudgetMs;

    public ControllerTask(UUID controllerId,
                   UUID userId,
                   ControllerTaskExecutor executor,
                   MonitorMetrics metrics,
                   PollHistoryService pollHistory,
                   int fetchBudgetMs) {
        this.controllerId  = controllerId;
        this.userId        = userId;
        this.executor      = executor;
        this.metrics       = metrics;
        this.pollHistory   = pollHistory;
        this.fetchBudgetMs = fetchBudgetMs;
    }

    @Override
    public void run() {
        Timer.Sample sample = Timer.start();
        try {
            executeTask();
        } finally {
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
            log.debug("⏭  Parser {} not available (CB open) — controller {} skipped", ctx.bookmaker(), controllerId);
            metrics.onPollCbSkipped();
            return;
        }

        Instant startedAt = Instant.now();
        long    startNs   = System.nanoTime();

        // External HTTP call wrapped with a hard budget timeout.
        List<ParsedItem> fetched;
        try {
            fetched = fetchWithBudget(ctx);
        } catch (TimeoutException e) {
            log.warn("⏱  Fetch budget exceeded ({}ms) for controller {} ({})", fetchBudgetMs, controllerId, ctx.bookmaker());
            pollHistory.record(controllerId, startedAt, msElapsed(startNs), -1, "timeout");
            metrics.onPollError();
            return;
        } catch (Exception e) {
            log.warn("⚠️  Ошибка парсера для контроллера {} ({}): {}", controllerId, ctx.bookmaker(), e.getMessage());
            pollHistory.record(controllerId, startedAt, msElapsed(startNs), -1, "error");
            metrics.onPollError();
            return;
        }

        // TX 2: dedup, persist, update timestamps, publish domain events
        int    eventsFound = 0;
        String status      = "ok";
        try {
            eventsFound = executor.persistNewEvents(ctx, fetched);
            if (eventsFound > 0) {
                metrics.onEventsDetected(eventsFound);
                log.debug("🔔 Контроллер {}: {} новых событий обнаружено", controllerId, eventsFound);
            }
        } catch (Exception e) {
            log.error("❌ Ошибка сохранения событий для контроллера {}: {}", controllerId, e.getMessage(), e);
            eventsFound = -1;
            status      = "error";
        }
        pollHistory.record(controllerId, startedAt, msElapsed(startNs), eventsFound, status);
        if ("ok".equals(status)) metrics.onPollOk(); else metrics.onPollError();
    }

    /**
     * Wraps {@link ControllerTaskExecutor#fetch} with a wall-clock budget.
     * Uses {@link CompletableFuture#orTimeout} so that a stuck HTTP call cannot
     * hold a worker-pool slot indefinitely (WebClient read/connect timeouts are a
     * first line of defence; this is the final backstop).
     */
    private List<ParsedItem> fetchWithBudget(TaskContext ctx) throws TimeoutException {
        try {
            return CompletableFuture
                    .supplyAsync(() -> executor.fetch(ctx))
                    .orTimeout(fetchBudgetMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof TimeoutException te) throw te;
            throw ce; // rethrown as RuntimeException, caught above
        }
    }

    private static long msElapsed(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
