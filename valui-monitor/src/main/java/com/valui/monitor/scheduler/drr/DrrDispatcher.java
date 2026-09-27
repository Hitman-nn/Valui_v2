package com.valui.monitor.scheduler.drr;

import com.valui.common.domain.BookmakerType;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.ControllerTask;
import com.valui.monitor.scheduler.ControllerTaskExecutor;
import com.valui.monitor.scheduler.MonitorMetrics;
import com.valui.monitor.scheduler.job.ControllerJob;
import com.valui.monitor.scheduler.job.ControllerJobEntry;
import com.valui.monitor.scheduler.job.JobRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central dispatch engine for the controller monitoring scheduler.
 *
 * <p>Architecture:
 * <ol>
 *   <li>A global {@link DelayQueue} holds {@link ControllerJobEntry} items ordered by {@code nextRunAt}.
 *   <li>A single platform dispatcher thread drains ready entries and routes them to per-user DRR queues.
 *   <li>The DRR round iterates users fairly; for each, it attempts to acquire a slot from that
 *       job's <em>bookmaker's own</em> semaphore (see {@link #slotsByBookmaker}).
 *   <li>If a slot is available the task is submitted to a virtual-thread worker pool (no-drop).
 *   <li>If that bookmaker's pool is full the job is returned to the delay queue with a small
 *       jitter delay (throttle, not skip) — other bookmakers are unaffected.
 * </ol>
 *
 * <p>Task completion re-enqueues the job with {@code nextRunAt = finishedAt + pollInterval}.
 *
 * <p><b>Why per-bookmaker, not one shared pool:</b> a single {@code Semaphore(maxConcurrentTasks)}
 * shared across every bookmaker was the structural cause behind two real production incidents
 * (Fonbet convoy 27.08, BetBoom reconnect storm 24.09) — one bookmaker's tasks piling up (stuck
 * behind a shared lock, or endlessly retrying a dead endpoint) exhausted the entire global budget,
 * so unrelated bookmakers' tasks got {@code InterruptedException} from dispatch starvation, not
 * from any problem of their own. Splitting {@code maxConcurrentTasks} into one reserved semaphore
 * per {@link BookmakerType} makes that structurally impossible: whatever happens to Fonbet's slots
 * can never take a slot away from XBet's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrrDispatcher {

    private final JobRegistry            jobRegistry;
    private final ControllerTaskExecutor taskExecutor;
    private final MonitorMetrics         metrics;
    private final PollHistoryService     pollHistory;
    private final MonitorProperties      props;

    // ── Core data structures (initialised in start()) ─────────────────────────

    /** Global time-ordered queue; entries become available when nextRunAt is reached. */
    private final DelayQueue<ControllerJobEntry> delayQueue = new DelayQueue<>();

    /** Tracks which controllers are currently in delayQueue; prevents phantom duplicates. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    /** Per-user DRR state. LinkedHashMap preserves round-robin insertion order. */
    private final LinkedHashMap<UUID, UserSchedulingState> userStates = new LinkedHashMap<>();

    /** Caps concurrent polls per bookmaker — one reserved semaphore each, never shared, so one
     *  bookmaker's outage/convoy can't starve dispatch slots from the others. See class javadoc. */
    private Map<BookmakerType, Semaphore> slotsByBookmaker;

    /** Each bookmaker's total (not available) slot count — fixed at {@link #start()}, only used
     *  for logging ("0/N free"), since {@code Semaphore} itself doesn't expose its original size. */
    private Map<BookmakerType, Integer> totalSlotsByBookmaker;

    /** Virtual-thread pool for task execution. */
    private ExecutorService workerPool;

    /** Platform dispatcher thread — single-threaded, drives the DRR loop. */
    private Thread dispatcherThread;

    private volatile boolean running = false;

    // Consecutive dispatch rounds where a given bookmaker's pool had zero free slots —
    // distinguishes a one-off burst (normal) from sustained saturation (capacity genuinely too
    // low for that bookmaker's current controller count, worth a WARN so it's visible without a
    // metrics dashboard). One counter per bookmaker now, not one global counter, since saturation
    // is per-bookmaker by construction.
    private final Map<BookmakerType, java.util.concurrent.atomic.AtomicInteger> consecutiveSaturatedRounds =
            new EnumMap<>(BookmakerType.class);
    private static final int SATURATION_WARN_EVERY = 50;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called by {@link com.valui.monitor.scheduler.MonitorScheduler} after Spring context is ready. */
    public void start() {
        BookmakerType[] types = BookmakerType.values();
        // Even split, floored — a few slots can be lost to integer division if maxConcurrentTasks
        // doesn't divide evenly by the number of bookmakers (e.g. 100/7=14, losing 2) — an
        // acceptable, visible-in-the-log tradeoff for guaranteeing no bookmaker can ever be
        // starved by another. At least 1 slot each even if maxConcurrentTasks < types.length.
        int perBookmaker = Math.max(1, props.getMaxConcurrentTasks() / types.length);
        slotsByBookmaker = new EnumMap<>(BookmakerType.class);
        totalSlotsByBookmaker = new EnumMap<>(BookmakerType.class);
        for (BookmakerType t : types) {
            slotsByBookmaker.put(t, new Semaphore(perBookmaker));
            totalSlotsByBookmaker.put(t, perBookmaker);
            consecutiveSaturatedRounds.put(t, new java.util.concurrent.atomic.AtomicInteger(0));
        }
        workerPool       = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("monitor-worker-", 0).factory());
        running          = true;
        dispatcherThread = Thread.ofPlatform()
                .name("monitor-dispatcher")
                .daemon(true)
                .start(this::dispatchLoop);
        log.info("[DRR] Dispatcher started: maxConcurrentTasks={} ({} bookmakers × {} slots each = {} " +
                "effective) defaultUserWeight={} fetchBudgetMs={} deferBaseMs={} deferJitterMs={}",
                props.getMaxConcurrentTasks(), types.length, perBookmaker, perBookmaker * types.length,
                props.getDefaultUserWeight(), props.getFetchBudgetMs(), props.getDeferBaseMs(),
                props.getDeferJitterMs());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (dispatcherThread != null) dispatcherThread.interrupt();
        if (workerPool != null) {
            workerPool.shutdown();
            try { workerPool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        log.info("[DRR] Dispatcher stopped");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Enqueue a job. Safe to call from any thread. No-op if already queued. */
    public void enqueue(ControllerJob job) {
        if (queued.add(job.controllerId())) {
            delayQueue.put(new ControllerJobEntry(job.controllerId(), job.nextRunAt()));
        }
    }

    /** Enqueue at a specific time (used internally for deferred/re-queued entries). No-op if already queued. */
    public void enqueueAt(UUID controllerId, Instant nextRunAt) {
        if (queued.add(controllerId)) {
            delayQueue.put(new ControllerJobEntry(controllerId, nextRunAt));
        }
    }

    /**
     * Remove a job from the delay queue.
     * The DRR per-user queues are cleaned lazily: if the controller is no longer in
     * {@link JobRegistry}, the dispatcher skips it when it dequeues.
     */
    public void cancel(UUID controllerId) {
        queued.remove(controllerId);
        delayQueue.removeIf(e -> e.controllerId().equals(controllerId));
    }

    public int queueDepth() {
        return delayQueue.size();
    }

    /** Sum of free slots across every bookmaker's own pool (0 = every pool saturated at once —
     *  extremely unlikely now that they're isolated; a single bookmaker being saturated no longer
     *  drives this to 0 by itself the way the old shared semaphore did). */
    public int availableSlots() {
        return slotsByBookmaker != null
                ? slotsByBookmaker.values().stream().mapToInt(Semaphore::availablePermits).sum()
                : 0;
    }

    // ── Dispatch loop (dispatcher thread) ────────────────────────────────────

    private void dispatchLoop() {
        while (running) {
            try {
                // Block until the next ready entry (up to 200 ms — keeps the loop responsive to stop()).
                ControllerJobEntry head = delayQueue.poll(200, TimeUnit.MILLISECONDS);
                if (head == null) continue;

                // Drain all other entries whose delay has also expired.
                List<ControllerJobEntry> batch = new ArrayList<>();
                batch.add(head);
                delayQueue.drainTo(batch);
                batch.forEach(e -> queued.remove(e.controllerId()));

                metrics.updateQueueDepth(delayQueue.size());

                routeBatch(batch);
                dispatchRound();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[DRR] Unexpected error in dispatch loop (queueDepth={} users={})",
                        delayQueue.size(), userStates.size(), e);
            }
        }
    }

    /** Route ready entries to per-user DRR queues; coalesce in-flight duplicates. */
    private void routeBatch(List<ControllerJobEntry> batch) {
        for (ControllerJobEntry entry : batch) {
            Optional<ControllerJob> jobOpt = jobRegistry.get(entry.controllerId());
            if (jobOpt.isEmpty()) continue; // controller was cancelled

            ControllerJob job = jobOpt.get();

            if (job.inFlight()) {
                // Task is still running — coalesce: defer until a bit after typical finish time.
                log.debug("[DRR] Coalesce (inFlight) controllerId={}", job.controllerId());
                enqueueAt(job.controllerId(), Instant.now().plusMillis(500));
                continue;
            }

            Duration lag = Duration.between(job.nextRunAt(), Instant.now());
            long lagMs = Math.max(0, lag.toMillis());
            metrics.onDispatchLag(lagMs);
            // A controller waiting many multiples of its own poll interval to even be routed to
            // a DRR queue (not yet the pool-saturation throttle below — this is queue-side delay)
            // is the direct symptom of scheduler starvation. Threshold is a flat 5s rather than
            // relative to pollIntervalSec since ControllerJob doesn't carry that here; still a
            // useful absolute floor given the default poll interval is 20s.
            if (lagMs > 5_000) {
                log.warn("[DRR] Large dispatch lag={}ms controllerId={} userId={} — possible starvation",
                        lagMs, job.controllerId(), job.userId());
            }

            userStates
                    .computeIfAbsent(job.userId(),
                            uid -> new UserSchedulingState(uid, props.getDefaultUserWeight()))
                    .enqueue(job.controllerId());
        }
    }

    /**
     * DRR dispatch round: serve each user fairly, attempt to submit tasks.
     * If a bookmaker's own pool is saturated, its jobs are returned to the delay queue with
     * jitter (throttle) — other bookmakers keep dispatching normally.
     */
    private void dispatchRound() {
        userStates.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (userStates.isEmpty()) return;

        boolean anyProgress;
        do {
            anyProgress = false;
            for (UserSchedulingState user : userStates.values()) {
                if (user.isEmpty()) continue;

                List<UUID> toRun = user.serve();
                for (UUID controllerId : toRun) {
                    // Skip cancelled controllers still lingering in DRR queues.
                    Optional<ControllerJob> jobOpt = jobRegistry.get(controllerId);
                    if (jobOpt.isEmpty()) continue;
                    BookmakerType bookmaker = jobOpt.get().bookmaker();
                    Semaphore slots = slotsByBookmaker.get(bookmaker);

                    if (slots.tryAcquire()) {
                        submitTask(controllerId, bookmaker);
                        anyProgress = true;
                        consecutiveSaturatedRounds.get(bookmaker).set(0);
                    } else {
                        // That bookmaker's pool is full — throttle: re-insert at head for priority
                        // next round and defer back into the delay queue with jitter.
                        user.requeue(controllerId);
                        metrics.onTaskDeferred();
                        long jitterMs = props.getDeferBaseMs()
                                + ThreadLocalRandom.current().nextInt(props.getDeferJitterMs() + 1);
                        enqueueAt(controllerId, Instant.now().plusMillis(jitterMs));
                        log.debug("[DRR] Deferred ({} pool full, jitter={}ms) controllerId={}",
                                bookmaker, jitterMs, controllerId);

                        // A single saturated round is unremarkable (normal at high concurrency);
                        // many rounds in a row with zero free slots for THIS bookmaker means its
                        // reserved share is genuinely too low for its current controller count (or
                        // it's stuck/convoying) — not a transient blip, and naming the bookmaker
                        // here (unlike the old single global counter) points straight at the
                        // culprit instead of just "the pool" in general.
                        int n = consecutiveSaturatedRounds.get(bookmaker).incrementAndGet();
                        if (n == 1) {
                            log.info("[DRR] {} pool saturated (0/{} slots free)",
                                    bookmaker, totalSlotsByBookmaker.get(bookmaker));
                        } else if (n % SATURATION_WARN_EVERY == 0) {
                            log.warn("[DRR] {} pool saturated for {} consecutive rounds — queueDepth={} " +
                                    "— consider raising its reserved share (maxConcurrentTasks={} total)",
                                    bookmaker, n, delayQueue.size(), props.getMaxConcurrentTasks());
                        }
                    }
                }
            }
        } while (anyProgress && userStates.values().stream().anyMatch(u -> !u.isEmpty()));

        userStates.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // ── Task submission ───────────────────────────────────────────────────────

    private void submitTask(UUID controllerId, BookmakerType bookmaker) {
        Semaphore slots = slotsByBookmaker.get(bookmaker);
        Optional<ControllerJob> jobOpt = jobRegistry.get(controllerId);
        if (jobOpt.isEmpty()) {
            log.debug("[DRR] Job vanished between dispatch and submit — controllerId={}", controllerId);
            slots.release();
            return;
        }
        ControllerJob job = jobOpt.get();
        ControllerJob inflight = job.markInFlight(Instant.now());
        jobRegistry.forceUpdate(inflight);

        boolean submitted = false;
        try {
            workerPool.submit(() -> {
                try {
                    new ControllerTask(
                            controllerId,
                            inflight.userId(),
                            taskExecutor,
                            metrics,
                            pollHistory,
                            props.getFetchBudgetMs()
                    ).run();
                } catch (Exception e) {
                    log.error("[DRR] Unhandled exception in task controllerId={}", controllerId, e);
                } finally {
                    slots.release();
                    onTaskComplete(controllerId);
                }
            });
            submitted = true;
        } finally {
            if (!submitted) {
                slots.release();
            }
        }
    }

    /** Called from virtual worker thread after task execution (success or failure). */
    private void onTaskComplete(UUID controllerId) {
        jobRegistry.get(controllerId).ifPresentOrElse(job -> {
            ControllerJob finished = job.markFinished(Instant.now());
            jobRegistry.forceUpdate(finished);
            enqueue(finished);
            log.debug("[DRR] Completed, next at {} — controllerId={}", finished.nextRunAt(), controllerId);
        }, () -> log.debug("[DRR] Task completed for controllerId={} but job no longer registered " +
                "(unscheduled mid-flight)", controllerId));
    }
}
