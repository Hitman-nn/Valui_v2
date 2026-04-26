package com.valui.monitor.dedup;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.scheduler.MonitorMetrics;
import com.valui.user.repository.DetectedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.valui.monitor.dedup.EventDeduplicationService.KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventDeduplicationService — unit tests")
class EventDeduplicationServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock SetOperations<String, String> setOps;
    @Mock DetectedEventRepository detectedRepo;

    SimpleMeterRegistry registry;
    MonitorMetrics metrics;
    MonitorProperties props;
    EventDeduplicationService dedup;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final String EVT_ID = "match-123";

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new MonitorMetrics(registry);
        props    = new MonitorProperties();
        props.setDedupTtlDays(7);

        given(redis.opsForSet()).willReturn(setOps);
        given(redis.expire(anyString(), any(Duration.class))).willReturn(Boolean.TRUE);

        dedup = new EventDeduplicationService(redis, detectedRepo, props, metrics);
    }

    // ── isNewEvent ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isNewEvent: event not in Redis → returns true (new)")
    void isNewEvent_notInRedis_returnsTrue() {
        given(setOps.isMember(anyString(), anyString())).willReturn(false);

        assertThat(dedup.isNewEvent(CTRL_ID, EVT_ID)).isTrue();
        verify(setOps).isMember(KEY_PREFIX + CTRL_ID, EVT_ID);
    }

    @Test
    @DisplayName("isNewEvent: event already in Redis → returns false (duplicate)")
    void isNewEvent_alreadyInRedis_returnsFalse() {
        given(setOps.isMember(anyString(), anyString())).willReturn(true);

        assertThat(dedup.isNewEvent(CTRL_ID, EVT_ID)).isFalse();
    }

    @Test
    @DisplayName("isNewEvent: hit increments cache.dedup.hit counter")
    void isNewEvent_hit_incrementsHitCounter() {
        given(setOps.isMember(anyString(), anyString())).willReturn(true);

        dedup.isNewEvent(CTRL_ID, EVT_ID);

        assertThat(registry.counter("cache.dedup.hit").count()).isEqualTo(1.0);
        assertThat(registry.counter("cache.dedup.miss").count()).isZero();
    }

    @Test
    @DisplayName("isNewEvent: miss increments cache.dedup.miss counter")
    void isNewEvent_miss_incrementsMissCounter() {
        given(setOps.isMember(anyString(), anyString())).willReturn(false);

        dedup.isNewEvent(CTRL_ID, EVT_ID);

        assertThat(registry.counter("cache.dedup.miss").count()).isEqualTo(1.0);
        assertThat(registry.counter("cache.dedup.hit").count()).isZero();
    }

    // ── claimIfNew ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("claimIfNew: first claim → SADD returns 1 → true + TTL refreshed")
    void claimIfNew_firstCall_returnsTrue() {
        given(setOps.add(anyString(), any(String[].class))).willReturn(1L);
        given(setOps.size(anyString())).willReturn(1L);

        boolean result = dedup.claimIfNew(CTRL_ID, EVT_ID);

        assertThat(result).isTrue();
        verify(setOps).add(eq(KEY_PREFIX + CTRL_ID), eq(EVT_ID));
        verify(redis).expire(eq(KEY_PREFIX + CTRL_ID), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("claimIfNew: second claim → SADD returns 0 → false")
    void claimIfNew_secondCall_returnsFalse() {
        given(setOps.add(anyString(), any(String[].class))).willReturn(0L);
        given(setOps.size(anyString())).willReturn(1L);

        assertThat(dedup.claimIfNew(CTRL_ID, EVT_ID)).isFalse();
    }

    @Test
    @DisplayName("claimIfNew: TTL refreshed on every call")
    void claimIfNew_refreshesTtl_everyCall() {
        given(setOps.add(anyString(), any(String[].class))).willReturn(1L);
        given(setOps.size(anyString())).willReturn(1L);

        dedup.claimIfNew(CTRL_ID, EVT_ID);
        dedup.claimIfNew(CTRL_ID, EVT_ID);

        verify(redis, times(2)).expire(eq(KEY_PREFIX + CTRL_ID), any(Duration.class));
    }

    // ── markBatchAsSeen ───────────────────────────────────────────────────────

    @Test
    @DisplayName("markBatchAsSeen: sends all IDs in one SADD")
    void markBatchAsSeen_batchSadd() {
        given(setOps.size(anyString())).willReturn(3L);

        dedup.markBatchAsSeen(CTRL_ID, Set.of("e1", "e2", "e3"));

        verify(setOps).add(eq(KEY_PREFIX + CTRL_ID), any(String[].class));
        verify(redis).expire(eq(KEY_PREFIX + CTRL_ID), any(Duration.class));
    }

    @Test
    @DisplayName("markBatchAsSeen: empty set is a no-op")
    void markBatchAsSeen_emptySet_noop() {
        dedup.markBatchAsSeen(CTRL_ID, Set.of());

        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    // ── seedIfAbsent ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("seedIfAbsent: key absent → seeds from DB")
    void seedIfAbsent_keyAbsent_seedsFromDB() {
        given(redis.hasKey(KEY_PREFIX + CTRL_ID)).willReturn(false);
        given(detectedRepo.findExternalIdsByControllerIdAndDetectedAtAfter(
                eq(CTRL_ID), any(OffsetDateTime.class)))
                .willReturn(List.of("db-evt-1", "db-evt-2"));
        given(setOps.size(anyString())).willReturn(2L);

        dedup.seedIfAbsent(CTRL_ID);

        verify(setOps).add(eq(KEY_PREFIX + CTRL_ID), eq("db-evt-1"), eq("db-evt-2"));
        verify(redis).expire(eq(KEY_PREFIX + CTRL_ID), any(Duration.class));
    }

    @Test
    @DisplayName("seedIfAbsent: key already exists → no DB query")
    void seedIfAbsent_keyPresent_skipsDB() {
        given(redis.hasKey(KEY_PREFIX + CTRL_ID)).willReturn(true);

        dedup.seedIfAbsent(CTRL_ID);

        verify(detectedRepo, never()).findExternalIdsByControllerIdAndDetectedAtAfter(any(), any());
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("seedIfAbsent: no DB events → no Redis write (empty SET not created)")
    void seedIfAbsent_noDbEvents_noRedisWrite() {
        given(redis.hasKey(KEY_PREFIX + CTRL_ID)).willReturn(false);
        given(detectedRepo.findExternalIdsByControllerIdAndDetectedAtAfter(any(), any()))
                .willReturn(List.of());

        dedup.seedIfAbsent(CTRL_ID);

        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    // ── syncSeenEvents ────────────────────────────────────────────────────────

    @Test
    @DisplayName("syncSeenEvents: stale Redis entries not in DB are removed")
    void syncSeenEvents_removesStale() {
        given(setOps.members(KEY_PREFIX + CTRL_ID))
                .willReturn(new HashSet<>(Set.of("stale", "good")));

        dedup.syncSeenEvents(CTRL_ID, Set.of("good"));

        verify(setOps).remove(eq(KEY_PREFIX + CTRL_ID), eq("stale"));
    }

    @Test
    @DisplayName("syncSeenEvents: DB entries absent from Redis are added (crash recovery)")
    void syncSeenEvents_addsRecovered() {
        given(setOps.members(KEY_PREFIX + CTRL_ID))
                .willReturn(new HashSet<>(Set.of("existing")));

        dedup.syncSeenEvents(CTRL_ID, Set.of("existing", "recovered"));

        verify(setOps).add(eq(KEY_PREFIX + CTRL_ID), eq("recovered"));
    }

    @Test
    @DisplayName("syncSeenEvents: identical sets → no Redis writes, no TTL refresh")
    void syncSeenEvents_noChanges_noWrites() {
        Set<String> same = Set.of("e1", "e2");
        given(setOps.members(KEY_PREFIX + CTRL_ID))
                .willReturn(new HashSet<>(same));

        dedup.syncSeenEvents(CTRL_ID, same);

        verify(setOps, never()).add(anyString(), any(String[].class));
        verify(setOps, never()).remove(anyString(), any(Object[].class));
    }

    // ── getSeenEventIds ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSeenEventIds: returns SMEMBERS result")
    void getSeenEventIds_returnsMembers() {
        given(setOps.members(KEY_PREFIX + CTRL_ID))
                .willReturn(new HashSet<>(Set.of("a", "b")));

        assertThat(dedup.getSeenEventIds(CTRL_ID)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("getSeenEventIds: null from Redis → empty set")
    void getSeenEventIds_nullResponse_emptySet() {
        given(setOps.members(KEY_PREFIX + CTRL_ID)).willReturn(null);

        assertThat(dedup.getSeenEventIds(CTRL_ID)).isEmpty();
    }

    // ── clearController ───────────────────────────────────────────────────────

    @Test
    @DisplayName("clearController: DEL is called on the Redis key")
    void clearController_deletesKey() {
        given(redis.delete(anyString())).willReturn(Boolean.TRUE);

        dedup.clearController(CTRL_ID);

        verify(redis).delete(KEY_PREFIX + CTRL_ID);
    }
}
