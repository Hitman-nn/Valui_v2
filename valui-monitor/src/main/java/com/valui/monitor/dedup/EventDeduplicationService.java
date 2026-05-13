package com.valui.monitor.dedup;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.scheduler.MonitorMetrics;
import com.valui.user.api.DetectedEventPortService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed event deduplication for controller tasks.
 *
 * Redis structure: "dedup:ctrl:{controllerId}" → Redis SET of eventExternalId strings.
 * TTL is refreshed to {@code dedupTtlDays} on every write.
 *
 * All public mutating methods are safe to call from virtual threads concurrently —
 * Redis SET operations (SADD, SREM, SISMEMBER) are atomic.
 */
@Slf4j
@Component
public class EventDeduplicationService {

    static final String KEY_PREFIX = "dedup:ctrl:";

    private final StringRedisTemplate redis;
    private final DetectedEventPortService detectedEventPort;
    private final MonitorProperties props;
    private final MonitorMetrics metrics;

    public EventDeduplicationService(StringRedisTemplate redis,
                                     DetectedEventPortService detectedEventPort,
                                     MonitorProperties props,
                                     MonitorMetrics metrics) {
        this.redis             = redis;
        this.detectedEventPort = detectedEventPort;
        this.props             = props;
        this.metrics           = metrics;
    }

    // ── Core dedup API ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the event has NOT been seen before (new event).
     * Updates the dedup hit/miss counter metric.
     */
    public boolean isNewEvent(UUID controllerId, String eventExternalId) {
        boolean isMember = Boolean.TRUE.equals(
                redis.opsForSet().isMember(key(controllerId), eventExternalId));
        if (isMember) {
            metrics.onDedupHit();
            return false;
        }
        metrics.onDedupMiss();
        return true;
    }

    /**
     * Atomically adds the event to the Redis SET and refreshes the TTL.
     * Returns {@code true} if this was the first time the event was seen
     * (i.e., it was actually added), {@code false} if it was already present.
     *
     * Prefer this over {@link #isNewEvent} + {@link #markAsSeen} to avoid TOCTOU races.
     */
    public boolean claimIfNew(UUID controllerId, String eventExternalId) {
        Long added = redis.opsForSet().add(key(controllerId), eventExternalId);
        boolean isNew = added != null && added > 0;
        refreshTtl(controllerId);
        if (isNew) {
            metrics.onDedupMiss();
        } else {
            metrics.onDedupHit();
        }
        return isNew;
    }

    public void markAsSeen(UUID controllerId, String eventExternalId) {
        redis.opsForSet().add(key(controllerId), eventExternalId);
        refreshTtl(controllerId);
        updateSizeGauge(controllerId);
    }

    public void markBatchAsSeen(UUID controllerId, Set<String> eventIds) {
        if (eventIds.isEmpty()) return;
        redis.opsForSet().add(key(controllerId), eventIds.toArray(String[]::new));
        refreshTtl(controllerId);
        updateSizeGauge(controllerId);
    }

    public Set<String> getSeenEventIds(UUID controllerId) {
        Set<String> members = redis.opsForSet().members(key(controllerId));
        return members != null ? Set.copyOf(members) : Set.of();
    }

    public void clearController(UUID controllerId) {
        redis.delete(key(controllerId));
        metrics.updateDedupSetSize(controllerId, 0);
        log.debug("Dedup cleared for controller {}", controllerId);
    }

    // ── Startup seeding ───────────────────────────────────────────────────────

    /**
     * Seeds the Redis SET from {@code detected_events} DB if the key doesn't yet exist.
     * Idempotent — safe to call on every startup.
     */
    @Transactional(readOnly = true)
    public void seedIfAbsent(UUID controllerId) {
        String redisKey = key(controllerId);
        if (Boolean.TRUE.equals(redis.hasKey(redisKey))) {
            log.debug("Dedup key already populated for controller {} — skipping seed", controllerId);
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(props.getDedupTtlDays());
        List<String> ids = detectedEventPort.findExternalIdsByControllerIdSince(controllerId, cutoff);
        if (!ids.isEmpty()) {
            redis.opsForSet().add(redisKey, ids.toArray(String[]::new));
            refreshTtl(controllerId);
            updateSizeGauge(controllerId);
            log.info("Seeded {} dedup entries for controller {} from DB", ids.size(), controllerId);
        } else {
            log.debug("No recent DB events to seed for controller {}", controllerId);
        }
    }

    // ── Nightly sync ──────────────────────────────────────────────────────────

    /**
     * Two-directional sync with separate authoritative sets per direction.
     *
     * @param allDbIds    ALL known event IDs for the controller (no time limit).
     *                    Used for remove: only evict what genuinely doesn't exist in DB at all.
     *                    This prevents false eviction of old-but-valid events whose detectedAt
     *                    falls outside the TTL window but are still in the Redis SET because a
     *                    newer event refreshed the key TTL.
     * @param recentDbIds Event IDs detected within the TTL window (cutoff = now - dedupTtlDays).
     *                    Used for add: recovers events missing from Redis after crash / restart.
     */
    public void syncSeenEvents(UUID controllerId, Set<String> allDbIds, Set<String> recentDbIds) {
        Set<String> inRedis = new HashSet<>(getSeenEventIds(controllerId));

        Set<String> toRemove = new HashSet<>(inRedis);
        toRemove.removeAll(allDbIds);

        Set<String> toAdd = new HashSet<>(recentDbIds);
        toAdd.removeAll(inRedis);

        if (!toRemove.isEmpty()) {
            redis.opsForSet().remove(key(controllerId), toRemove.toArray(Object[]::new));
            log.warn("[DEDUP-SYNC] Removed {} phantom Redis entries for controller {} — present in Redis but absent from DB. " +
                     "Possible cause: migration seeded Redis without populating detected_events.",
                    toRemove.size(), controllerId);
        }
        if (!toAdd.isEmpty()) {
            redis.opsForSet().add(key(controllerId), toAdd.toArray(String[]::new));
            log.debug("Sync added {} recovered entries to Redis for controller {}", toAdd.size(), controllerId);
        }
        if (!toRemove.isEmpty() || !toAdd.isEmpty()) {
            refreshTtl(controllerId);
            updateSizeGauge(controllerId);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void refreshTtl(UUID controllerId) {
        redis.expire(key(controllerId), Duration.ofDays(props.getDedupTtlDays()));
    }

    private void updateSizeGauge(UUID controllerId) {
        Long size = redis.opsForSet().size(key(controllerId));
        metrics.updateDedupSetSize(controllerId, size != null ? size.intValue() : 0);
    }

    static String key(UUID controllerId) {
        return KEY_PREFIX + controllerId;
    }
}
