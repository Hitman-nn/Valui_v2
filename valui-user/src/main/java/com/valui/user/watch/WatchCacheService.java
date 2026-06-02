package com.valui.user.watch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed store for market-watch button data.
 *
 * Key:  {@code watch:{notifLogId}}
 * TTL:  dynamic — capped to match start time + 2 h (same logic as notification dedup),
 *       minimum 24 h so the button stays alive for recently-started or unknown-start matches.
 *
 * Written by {@code SportEventConsumer} (valui-notify) for TOURNAMENT/MATCH events where
 * at least one market (handicap or total) is absent.
 * Read by {@code WatchMarketCallback} (valui-bot) when the user taps the watch button.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchCacheService {

    static final String    KEY_PREFIX   = "watch:";
    static final Duration  MIN_TTL      = Duration.ofHours(24);
    static final Duration  MAX_TTL      = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public void store(String notifLogId, WatchCacheData data) {
        Duration ttl = computeTtl(data.startEpoch());
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + notifLogId,
                    objectMapper.writeValueAsString(data), ttl);
        } catch (JsonProcessingException e) {
            log.error("[WATCH-CACHE] Failed to serialize for notifLogId={}: {}", notifLogId, e.getMessage());
        }
    }

    public Optional<WatchCacheData> find(String notifLogId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + notifLogId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, WatchCacheData.class));
        } catch (Exception e) {
            log.error("[WATCH-CACHE] Failed to read for notifLogId={}: {}", notifLogId, e.getMessage());
            return Optional.empty();
        }
    }

    private static Duration computeTtl(Long startEpoch) {
        if (startEpoch == null || startEpoch <= 0) return MIN_TTL;
        long secondsUntilStart = startEpoch - System.currentTimeMillis() / 1000;
        if (secondsUntilStart <= 0) return MIN_TTL;
        Duration dynamic = Duration.ofSeconds(secondsUntilStart).plusHours(2);
        if (dynamic.compareTo(MIN_TTL) < 0) return MIN_TTL;
        if (dynamic.compareTo(MAX_TTL) > 0) return MAX_TTL;
        return dynamic;
    }
}
