package com.valui.monitor.scheduler.job;

import com.valui.monitor.scheduler.state.SchedulerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of all active {@link ControllerJob}s, mirrored to Redis on every write.
 * Thread-safe; read operations are wait-free.
 */
@Component
@RequiredArgsConstructor
public class JobRegistry {

    private final ConcurrentHashMap<UUID, ControllerJob> jobs = new ConcurrentHashMap<>();
    private final SchedulerStateStore stateStore;

    /** Register or replace a job unconditionally (lifecycle events, startup). */
    public void put(ControllerJob job) {
        jobs.put(job.controllerId(), job);
        stateStore.save(job);
    }

    /**
     * CAS-style update: succeeds only when the stored version equals {@code updated.version() - 1}.
     * Returns {@code true} if the update was applied.
     */
    public boolean update(ControllerJob updated) {
        boolean[] applied = {false};
        jobs.compute(updated.controllerId(), (id, current) -> {
            if (current != null && current.version() == updated.version() - 1) {
                applied[0] = true;
                return updated;
            }
            return current;
        });
        if (applied[0]) stateStore.save(updated);
        return applied[0];
    }

    /** Force-update without version check (completion callbacks, lifecycle resets). */
    public void forceUpdate(ControllerJob job) {
        jobs.put(job.controllerId(), job);
        stateStore.save(job);
    }

    public void remove(UUID controllerId) {
        jobs.remove(controllerId);
        stateStore.delete(controllerId);
    }

    public Optional<ControllerJob> get(UUID controllerId) {
        return Optional.ofNullable(jobs.get(controllerId));
    }

    public boolean contains(UUID controllerId) {
        return jobs.containsKey(controllerId);
    }

    public Collection<ControllerJob> all() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    public Set<UUID> controllerIds() {
        return Collections.unmodifiableSet(jobs.keySet());
    }

    public int size() {
        return jobs.size();
    }
}
