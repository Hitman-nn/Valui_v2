package com.valui.monitor.dedup;

import com.valui.common.entity.ControllerEntity;
import com.valui.monitor.config.MonitorProperties;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.DetectedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
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
    private final ControllerRepository controllerRepo;
    private final DetectedEventRepository detectedRepo;
    private final MonitorProperties props;

    @Scheduled(cron = "${valui.monitor.dedup-sync-cron:0 0 3 * * *}")
    @Transactional(readOnly = true)
    public void sync() {
        log.info("Nightly dedup sync started");
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(props.getDedupTtlDays());

        List<ControllerEntity> active = controllerRepo.findAllByIsActiveTrue();
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
        List<String> dbIds = detectedRepo
                .findExternalIdsByControllerIdAndDetectedAtAfter(controllerId, cutoff);
        dedup.syncSeenEvents(controllerId, new HashSet<>(dbIds));
    }
}
