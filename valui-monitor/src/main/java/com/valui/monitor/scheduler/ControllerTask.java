package com.valui.monitor.scheduler;

import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ParsedItem;
import com.valui.monitor.scheduler.ControllerTaskExecutor.TaskContext;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
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
        try (var ctrlCtx = MDC.putCloseable("controllerId", controllerId.toString())) {
            // TX 1: load fresh controller context
            Optional<TaskContext> ctxOpt = executor.loadContext(controllerId);
            if (ctxOpt.isEmpty()) {
                // Overwhelmingly the normal case (controller was deactivated between schedule
                // and poll) — a genuine URL-parse failure is already logged with full detail by
                // ControllerTaskExecutor.loadContext() itself, so this stays a plain DEBUG note.
                log.debug("Controller not active — skipping poll");
                return;
            }
            TaskContext ctx = ctxOpt.get();

            // bookmaker key scoped to the block where ctx is available
            try (var bkCtx = MDC.putCloseable("bookmaker", ctx.bookmaker().name())) {
                if (!executor.isParserAvailable(ctx.bookmaker())) {
                    log.debug("Parser not available (CB open) — skipping");
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
                    log.warn("Fetch budget exceeded ({}ms) tournamentId={} sportId={} url={}",
                            fetchBudgetMs, ctx.tournamentId(), ctx.sportId(), ctx.url());
                    pollHistory.record(controllerId, startedAt, msElapsed(startNs), -1, "timeout");
                    metrics.onPollError();
                    return;
                } catch (Exception e) {
                    // e.toString() not e.getMessage(): many exception types (NPE, some IOException
                    // subclasses) have a null message, which would otherwise log as the useless
                    // "Parser error: null" — toString() always includes the exception class name.
                    log.warn("Parser error tournamentId={} sportId={}: {}", ctx.tournamentId(), ctx.sportId(), e.toString());
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
                        log.debug("{} new event(s) detected", eventsFound);
                    }
                } catch (Exception e) {
                    log.error("Event persistence failed: {}", e.getMessage(), e);
                    eventsFound = -1;
                    status      = "error";
                }

                // TX 3 (independent): check if any watched market appeared in this fetch
                try {
                    executor.checkMarketWatches(controllerId, fetched);
                } catch (Exception e) {
                    log.warn("Market watch check failed: {}", e.getMessage());
                }
                pollHistory.record(controllerId, startedAt, msElapsed(startNs), eventsFound, status);
                if ("ok".equals(status)) metrics.onPollOk(); else metrics.onPollError();
            }
        }
    }

    /** Extra grace period after interrupting a timed-out fetch, to confirm it actually exited. */
    private static final long JOIN_GRACE_MS = 2_000;

    /**
     * Wraps {@link ControllerTaskExecutor#fetch} with a wall-clock budget.
     *
     * <p>Spawns a child virtual thread for the HTTP work and bounds the wait with
     * {@link Thread#join(Duration)} — no separate latch is needed since {@code join} already
     * blocks up to the given timeout and returns early once the thread finishes.
     *
     * <p>On timeout the child is interrupted — {@code Mono.block()} inside the parsers uses
     * {@code CountDownLatch.await()}, which responds to {@link Thread#interrupt()} by throwing
     * {@link InterruptedException}, causing Reactor to cancel the subscription and release its
     * heap objects. That cancellation isn't instantaneous, so we join again with a short grace
     * period and log if the child is still alive after it — rather than assuming the interrupt
     * always lands immediately.
     *
     * <p>The previous {@link CompletableFuture#supplyAsync} approach submitted to
     * {@code ForkJoinPool.commonPool()} (platform threads). On budget expiry the
     * ForkJoinPool task kept running its {@code Mono.block(20 s)} call for up to 12 more
     * seconds — accumulating live Reactor pipelines and JSON parse trees in the heap until
     * GC pressure caused OOM.
     */
    private List<ParsedItem> fetchWithBudget(TaskContext ctx) throws TimeoutException {
        var result = new java.util.concurrent.atomic.AtomicReference<List<ParsedItem>>();
        var error  = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        Thread child = Thread.ofVirtual()
                .name("monitor-fetch-" + controllerId)
                .start(() -> {
                    try {
                        result.set(executor.fetch(ctx));
                    } catch (Throwable t) {
                        error.set(t);
                    }
                });

        try {
            child.join(Duration.ofMillis(fetchBudgetMs));
        } catch (InterruptedException ie) {
            child.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }

        if (child.isAlive()) {
            child.interrupt(); // unparks Mono.block() → subscription cancelled → heap freed
            awaitChildExit(child);
            throw new TimeoutException("Fetch budget exceeded: " + fetchBudgetMs + "ms");
        }

        Throwable t = error.get();
        if (t instanceof RuntimeException re) throw re;
        if (t != null) throw new RuntimeException(t);
        return result.get();
    }

    /** Best-effort confirmation that an interrupted fetch thread actually terminated. */
    private void awaitChildExit(Thread child) {
        try {
            child.join(Duration.ofMillis(JOIN_GRACE_MS));
            if (child.isAlive()) {
                log.warn("Fetch thread {} still alive {}ms after interrupt on budget timeout",
                        child.getName(), JOIN_GRACE_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static long msElapsed(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
