package com.valui.monitor.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.scheduler.drr.DrrDispatcher;
import com.valui.monitor.scheduler.job.JobRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Snapshots scheduler metrics every 30 seconds into a Redis ring buffer.
 *
 * <p>Key: {@code sch:metrics:history} — Redis List (newest at index 0).
 * Keeps {@code MAX_POINTS} = 2880 entries = 24 hours at 30s resolution.
 * TTL on the key: 25 hours (1 hour buffer for restart gaps).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerMetricsHistoryService {

    static final String  KEY        = "sch:metrics:history";
    static final int     MAX_POINTS = 2880;   // 24h at 30s
    static final Duration KEY_TTL   = Duration.ofHours(25);

    private final StringRedisTemplate redis;
    private final ObjectMapper        mapper;
    private final MeterRegistry       meterRegistry;
    private final JobRegistry         jobRegistry;
    private final DrrDispatcher       dispatcher;
    private final MonitorProperties   props;

    @Scheduled(fixedDelay = 30_000)
    public void snapshot() {
        try {
            SchedulerMetricsSnapshot snap = new SchedulerMetricsSnapshot(
                    System.currentTimeMillis(),
                    dispatcher.queueDepth(),
                    props.getMaxConcurrentTasks() - dispatcher.availableSlots(),
                    dispatcher.availableSlots(),
                    safeHistogram("monitor.scheduler.dispatch.lag.ms", 0.95),
                    safeTimer("monitor.task.duration", 0.95),
                    safeCounter("monitor.tasks.deferred"),
                    safeCounter("monitor.events.detected")
            );
            String json = mapper.writeValueAsString(snap);
            redis.opsForList().leftPush(KEY, json);
            redis.opsForList().trim(KEY, 0, MAX_POINTS - 1);
            redis.expire(KEY, KEY_TTL);
        } catch (Exception e) {
            log.debug("[SchedulerMetricsHistory] Snapshot failed: {}", e.getMessage());
        }
    }

    /**
     * Returns up to {@code maxPoints} most recent snapshots.
     *
     * @param range "1h" → 120 points, "6h" → 720, "24h" → 2880
     */
    public List<SchedulerMetricsSnapshot> getHistory(String range) {
        int points = switch (range) {
            case "1h"  -> 120;
            case "6h"  -> 720;
            default    -> MAX_POINTS; // "24h"
        };
        List<String> raw = redis.opsForList().range(KEY, 0, points - 1);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        // reverse so oldest → newest for chart
        List<SchedulerMetricsSnapshot> result = raw.stream()
                .map(this::deserialize)
                .filter(s -> s != null)
                .collect(Collectors.toList());
        Collections.reverse(result);
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private double safeHistogram(String name, double pct) {
        try { return meterRegistry.find(name).summary().percentile(pct); }
        catch (Exception e) { return 0; }
    }

    private double safeTimer(String name, double pct) {
        try { return meterRegistry.find(name).timer().percentile(pct, TimeUnit.MILLISECONDS); }
        catch (Exception e) { return 0; }
    }

    private long safeCounter(String name) {
        try { return (long) meterRegistry.find(name).counter().count(); }
        catch (Exception e) { return 0; }
    }

    private SchedulerMetricsSnapshot deserialize(String json) {
        try { return mapper.readValue(json, SchedulerMetricsSnapshot.class); }
        catch (JsonProcessingException e) { return null; }
    }
}
