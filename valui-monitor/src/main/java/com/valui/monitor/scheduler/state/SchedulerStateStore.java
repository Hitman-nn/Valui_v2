package com.valui.monitor.scheduler.state;

import com.valui.monitor.scheduler.job.ControllerJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis persistence for scheduler job state.
 * Key format: {@code sch:job:{controllerId}} (Hash with fields nextRunAt, inFlight, version, …).
 * TTL: 48 h, refreshed on every save — guards against leaks when a controller is deleted
 * during downtime (explicit delete still works for normal removal).
 *
 * All methods swallow Redis errors (state store is best-effort; in-memory registry is authoritative).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerStateStore {

    private static final String  PREFIX = "sch:job:";
    private static final Duration TTL   = Duration.ofHours(48);

    private final StringRedisTemplate redis;

    public void save(ControllerJob job) {
        try {
            String key = PREFIX + job.controllerId();
            Map<String, String> fields = new HashMap<>();
            fields.put("nextRunAt", job.nextRunAt().toString());
            fields.put("inFlight",  String.valueOf(job.inFlight()));
            fields.put("version",   String.valueOf(job.version()));
            if (job.lastStartedAt()  != null) fields.put("lastStartedAt",  job.lastStartedAt().toString());
            if (job.lastFinishedAt() != null) fields.put("lastFinishedAt", job.lastFinishedAt().toString());
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[SchedulerState] save failed for {}: {}", job.controllerId(), e.getMessage());
        }
    }

    /** Returns the persisted nextRunAt if present and parseable, otherwise empty. */
    public Optional<Instant> loadNextRunAt(UUID controllerId) {
        try {
            Object val = redis.opsForHash().get(PREFIX + controllerId, "nextRunAt");
            return val == null ? Optional.empty() : Optional.of(Instant.parse(val.toString()));
        } catch (Exception e) {
            log.warn("[SchedulerState] loadNextRunAt failed for {}: {}", controllerId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Clears a stuck {@code inFlight} flag left by a JVM crash.
     * Called during startup for every controller being re-registered.
     */
    public void clearInFlight(UUID controllerId) {
        try {
            redis.opsForHash().put(PREFIX + controllerId, "inFlight", "false");
        } catch (Exception e) {
            log.warn("[SchedulerState] clearInFlight failed for {}: {}", controllerId, e.getMessage());
        }
    }

    public void delete(UUID controllerId) {
        try {
            redis.delete(PREFIX + controllerId);
        } catch (Exception e) {
            log.warn("[SchedulerState] delete failed for {}: {}", controllerId, e.getMessage());
        }
    }
}
