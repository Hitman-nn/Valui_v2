package com.valui.app.jobs;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of executions for each @Scheduled task.
 * Populated by {@link ScheduledTaskAspect}; queried by {@link AdminJobsController}.
 *
 * NOTE: counters (runCount, errorCount) are in-memory only and reset on application restart.
 */
@Component
public class ScheduledTaskTracker {

    public record TaskExecution(
            Instant lastRunAt,
            long    durationMs,
            String  status,
            String  lastErrorMessage,  // null on success; always non-null on ERROR
            int     runCount,
            int     errorCount
    ) {}

    private final ConcurrentHashMap<String, TaskExecution> executions = new ConcurrentHashMap<>();

    void record(String key, long durationMs, boolean success, String errorMessage) {
        executions.merge(
            key,
            new TaskExecution(Instant.now(), durationMs,
                    success ? "OK" : "ERROR", errorMessage, 1, success ? 0 : 1),
            (prev, next) -> new TaskExecution(
                    next.lastRunAt(), next.durationMs(), next.status(), next.lastErrorMessage(),
                    prev.runCount() + 1,
                    prev.errorCount() + (success ? 0 : 1))
        );
    }

    public Map<String, TaskExecution> getAll() {
        return Collections.unmodifiableMap(executions);
    }
}
