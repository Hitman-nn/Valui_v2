package com.valui.app.jobs;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of the most recent execution for each @Scheduled task.
 * Populated by {@link ScheduledTaskAspect}; queried by {@link AdminJobsController}.
 */
@Component
public class ScheduledTaskTracker {

    public record TaskExecution(Instant lastRunAt, long durationMs, String status) {}

    private final ConcurrentHashMap<String, TaskExecution> executions = new ConcurrentHashMap<>();

    void record(String key, long durationMs, boolean success) {
        executions.put(key, new TaskExecution(Instant.now(), durationMs, success ? "OK" : "ERROR"));
    }

    public Map<String, TaskExecution> getAll() {
        return Collections.unmodifiableMap(executions);
    }
}
