package com.valui.monitor.dedup;

import com.valui.common.entity.ControllerEntity;
import com.valui.monitor.config.MonitorProperties;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Nightly reconciliation of the Redis dedup SET against the authoritative detected_events DB table.
 *
 * Run at 3 AM by default (configurable via {@code valui.monitor.dedup-sync-cron}).
 * Two-way sync:
 *   - Removes from Redis event IDs absent from DB (e.g., manual deletion).
 *   - Adds to Redis event IDs present in DB but absent from Redis (crash/TTL recovery).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DedupSyncScheduler {

    private final EventDeduplicationService dedup;
    private final ControllerPortService controllerPort;
    private final DetectedEventPortService detectedEventPort;
    private final MonitorProperties props;

    @Scheduled(cron = "${valui.monitor.dedup-sync-cron:0 0 3 * * *}")
    public void sync() {
        log.info("Nightly dedup sync started");
        OffsetDateTime now = OffsetDateTime.now();

        // Step 1: delete expired detected_events in batches to avoid long-running DELETE.
        // Must happen BEFORE the Redis sync so removed IDs are absent from allDbIds →
        // syncSeenEvents() evicts them from the Redis SET automatically.
        // Wrapped separately so a DB failure here doesn't abort the Redis reconciliation.
        try {
            int totalDeleted = 0;
            int deleted;
            do {
                deleted = detectedEventPort.deleteExpiredBatch(now, props.getDedupCleanupBatchSize());
                totalDeleted += deleted;
            } while (deleted > 0);
            if (totalDeleted > 0) {
                log.info("Nightly cleanup: deleted {} expired detected_events rows", totalDeleted);
            }
        } catch (Exception e) {
            log.error("Nightly cleanup failed, continuing with Redis sync: {}", e.getMessage(), e);
        }

        // Step 2: two-way Redis ↔ DB reconciliation per controller.
        OffsetDateTime cutoff = now.minusDays(props.getDedupTtlDays());
        List<ControllerEntity> active = controllerPort.findAllActive();
        int synced = 0, errors = 0;

        for (ControllerEntity ctrl : active) {
            try {
                syncOne(ctrl.getId(), cutoff);
                synced++;
            } catch (Exception e) {
                log.error("Dedup sync failed for controller {}: {}", ctrl.getId(), e.getMessage(), e);
                errors++;
            }
        }
        log.info("Nightly dedup sync completed: synced={} errors={} total={}", synced, errors, active.size());
    }

    private void syncOne(UUID controllerId, OffsetDateTime cutoff) {
        // toRemove: all-time DB set — only evict from Redis what genuinely doesn't exist in DB at all
        Set<String> allDbIds = new HashSet<>(
                detectedEventPort.findAllExternalIdsByControllerId(controllerId));

        // toAdd: recent DB set — recover events within the TTL window that are missing from Redis
        Set<String> recentDbIds = new HashSet<>(
                detectedEventPort.findExternalIdsByControllerIdSince(controllerId, cutoff));

        dedup.syncSeenEvents(controllerId, allDbIds, recentDbIds);
    }
}
