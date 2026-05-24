package com.valui.monitor.scheduler.drr;

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
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>The DRR round iterates users fairly; for each, it attempts to acquire a global semaphore slot.
 *   <li>If a slot is available the task is submitted to a virtual-thread worker pool (no-drop).
 *   <li>If the pool is full the job is returned to the delay queue with a small jitter delay (throttle, not skip).
 * </ol>
 *
 * <p>Task completion re-enqueues the job with {@code nextRunAt = finishedAt + pollInterval}.
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

    /** Caps total concurrent polls across all users. */
    private Semaphore globalSlots;

    /** Virtual-thread pool for task execution. */
    private ExecutorService workerPool;

    /** Platform dispatcher thread — single-threaded, drives the DRR loop. */
    private Thread dispatcherThread;

    private volatile boolean running = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called by {@link com.valui.monitor.scheduler.MonitorScheduler} after Spring context is ready. */
    public void start() {
        globalSlots      = new Semaphore(props.getMaxConcurrentTasks());
        workerPool       = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("monitor-worker-", 0).factory());
        running          = true;
        dispatcherThread = Thread.ofPlatform()
                .name("monitor-dispatcher")
                .daemon(true)
                .start(this::dispatchLoop);
        log.info("[DRR] Dispatcher started (maxConcurrentTasks={})", props.getMaxConcurrentTasks());
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

    /** Number of free global slots (0 = pool saturated). */
    public int availableSlots() {
        return globalSlots != null ? globalSlots.availablePermits() : 0;
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
                log.error("[DRR] Unexpected error in dispatch loop", e);
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
            metrics.onDispatchLag(Math.max(0, lag.toMillis()));

            userStates
                    .computeIfAbsent(job.userId(),
                            uid -> new UserSchedulingState(uid, props.getDefaultUserWeight()))
                    .enqueue(job.controllerId());
        }
    }

    /**
     * DRR dispatch round: serve each user fairly, attempt to submit tasks.
     * If the global pool is saturated, jobs are returned to the delay queue with jitter (throttle).
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
                    if (!jobRegistry.contains(controllerId)) continue;

                    if (globalSlots.tryAcquire()) {
                        submitTask(controllerId);
                        anyProgress = true;
                    } else {
                        // Pool is full — throttle: re-insert at head for priority next round
                        // and defer back into the delay queue with jitter.
                        user.requeue(controllerId);
                        metrics.onTaskDeferred();
                        long jitterMs = props.getDeferBaseMs()
                                + ThreadLocalRandom.current().nextInt(props.getDeferJitterMs() + 1);
                        enqueueAt(controllerId, Instant.now().plusMillis(jitterMs));
                        log.debug("[DRR] Deferred (pool full, jitter={}ms) controllerId={}", jitterMs, controllerId);
                    }
                }
            }
        } while (anyProgress && userStates.values().stream().anyMatch(u -> !u.isEmpty()));

        userStates.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // ── Task submission ───────────────────────────────────────────────────────

    private void submitTask(UUID controllerId) {
        Optional<ControllerJob> jobOpt = jobRegistry.get(controllerId);
        if (jobOpt.isEmpty()) {
            globalSlots.release();
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
                    globalSlots.release();
                    onTaskComplete(controllerId);
                }
            });
            submitted = true;
        } finally {
            if (!submitted) {
                globalSlots.release();
            }
        }
    }

    /** Called from virtual worker thread after task execution (success or failure). */
    private void onTaskComplete(UUID controllerId) {
        jobRegistry.get(controllerId).ifPresent(job -> {
            ControllerJob finished = job.markFinished(Instant.now());
            jobRegistry.forceUpdate(finished);
            enqueue(finished);
            log.debug("[DRR] Completed, next at {} — controllerId={}", finished.nextRunAt(), controllerId);
        });
    }
}
