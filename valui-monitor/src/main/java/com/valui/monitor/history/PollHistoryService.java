package com.valui.monitor.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records poll execution history.
 *
 * <p>Dual-write strategy:
 * <ul>
 *   <li>Redis List — fast {@link #getLast} (last 5 per controller, no DB hit)
 *   <li>PostgreSQL {@code poll_history} — durable, supports {@link #getHourlyStats} analytics
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollHistoryService {

    static final String KEY_PREFIX = "poll:history:";
    static final int    MAX_REDIS  = 5;

    private final StringRedisTemplate        redis;
    private final ObjectMapper               mapper;
    private final JdbcTemplate               jdbc;
    private final PlatformTransactionManager txManager;

    // ── Write ─────────────────────────────────────────────────────────────────

    @Transactional
    public void record(UUID controllerId, Instant startedAt, long durationMs,
                       int eventsFound, String status) {
        writeRedis(controllerId, startedAt, durationMs, eventsFound, status);
        writeDb(controllerId, startedAt, durationMs, eventsFound, status);
    }

    // ── Read — fast path (Redis) ───────────────────────────────────────────────

    public List<PollHistoryEntry> getLast(UUID controllerId) {
        List<String> raw = redis.opsForList().range(key(controllerId), 0, MAX_REDIS - 1);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        return raw.stream()
                .map(this::deserialize)
                .filter(e -> e != null)
                .collect(Collectors.toList());
    }

    // ── Read — analytics (PostgreSQL) ─────────────────────────────────────────

    /**
     * Returns per-hour aggregates for a single controller over the last {@code hours} hours.
     * Used for the admin scheduler analytics page.
     */
    public List<PollHistoryHourlyDto> getHourlyStats(UUID controllerId, int hours) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS);
        return jdbc.query("""
                SELECT DATE_TRUNC('hour', started_at)     AS hour,
                       COUNT(*)                            AS total_polls,
                       ROUND(AVG(duration_ms))             AS avg_duration_ms,
                       SUM(CASE WHEN status = 'ok'    THEN 1 ELSE 0 END) AS ok_count,
                       SUM(CASE WHEN status <> 'ok'   THEN 1 ELSE 0 END) AS error_count,
                       SUM(GREATEST(events_found, 0))      AS total_events
                FROM poll_history
                WHERE controller_id = ? AND started_at >= ?
                GROUP BY DATE_TRUNC('hour', started_at)
                ORDER BY hour
                """,
                (rs, i) -> new PollHistoryHourlyDto(
                        rs.getTimestamp("hour").toInstant(),
                        rs.getLong("total_polls"),
                        rs.getLong("avg_duration_ms"),
                        rs.getLong("ok_count"),
                        rs.getLong("error_count"),
                        rs.getLong("total_events")
                ),
                controllerId, java.sql.Timestamp.from(from));
    }

    /**
     * Global error rate per hour across ALL controllers (for scheduler overview chart).
     */
    public List<Map<String, Object>> getGlobalHourlyStats(int hours) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS);
        return jdbc.queryForList("""
                SELECT DATE_TRUNC('hour', started_at)         AS hour,
                       COUNT(*)                                AS total_polls,
                       SUM(CASE WHEN status <> 'ok' THEN 1 ELSE 0 END) AS errors,
                       SUM(GREATEST(events_found, 0))          AS total_events,
                       ROUND(AVG(duration_ms))                 AS avg_duration_ms
                FROM poll_history
                WHERE started_at >= ?
                GROUP BY DATE_TRUNC('hour', started_at)
                ORDER BY hour
                """,
                java.sql.Timestamp.from(from));
    }

    // ── Purge ─────────────────────────────────────────────────────────────────

    /**
     * Deletes rows older than 7 days in batches of 10 000 to avoid holding a long table lock.
     * Each batch is its own transaction so concurrent inserts and reads are not blocked.
     * Runs nightly at 2 AM.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeOld() {
        var tx = new TransactionTemplate(txManager);
        int total = 0;
        int batch;
        do {
            Integer deleted = tx.execute(status -> jdbc.update("""
                    DELETE FROM poll_history WHERE id IN (
                        SELECT id FROM poll_history
                        WHERE started_at < now() - INTERVAL '7 days'
                        LIMIT 10000
                    )"""));
            batch = deleted != null ? deleted : 0;
            total += batch;
        } while (batch > 0);
        if (total > 0) log.info("[PollHistory] Purged {} old records", total);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void writeRedis(UUID controllerId, Instant startedAt,
                            long durationMs, int eventsFound, String status) {
        try {
            String json = mapper.writeValueAsString(
                    new PollHistoryEntry(startedAt, durationMs, eventsFound, status));
            String key = key(controllerId);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, MAX_REDIS - 1);
        } catch (JsonProcessingException e) {
            log.debug("Redis poll history write failed for {}: {}", controllerId, e.getMessage());
        }
    }

    private void writeDb(UUID controllerId, Instant startedAt,
                         long durationMs, int eventsFound, String status) {
        try {
            jdbc.update("""
                    INSERT INTO poll_history (controller_id, started_at, duration_ms, events_found, status)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    controllerId,
                    java.sql.Timestamp.from(startedAt),
                    durationMs,
                    eventsFound,
                    status);
        } catch (Exception e) {
            log.warn("[PollHistory] DB write failed for {}: {}", controllerId, e.getMessage());
        }
    }

    private PollHistoryEntry deserialize(String json) {
        try {
            return mapper.readValue(json, PollHistoryEntry.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String key(UUID controllerId) { return KEY_PREFIX + controllerId; }
}
