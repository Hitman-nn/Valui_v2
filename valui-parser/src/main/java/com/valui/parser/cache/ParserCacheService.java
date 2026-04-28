package com.valui.parser.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParserCacheService {

    private static final String KEY_SPORTS      = "parser:sports:%s";
    private static final String KEY_TOURNAMENTS = "parser:tournaments:%s:%s";
    private static final String KEY_MATCHES     = "parser:matches:%s:%s";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ParserCacheProperties props;

    // ── Sports ────────────────────────────────────────────────────────────────

    public Optional<List<SportDto>> getSports(BookmakerType bk) {
        return get(sportsKey(bk), new TypeReference<>() {});
    }

    public void setSports(BookmakerType bk, List<SportDto> data) {
        set(sportsKey(bk), data, props.getSportsTtl());
    }

    // ── Tournaments ───────────────────────────────────────────────────────────

    public Optional<List<TournamentDto>> getTournaments(BookmakerType bk, String sportId) {
        return get(tournamentsKey(bk, sportId), new TypeReference<>() {});
    }

    public void setTournaments(BookmakerType bk, String sportId, List<TournamentDto> data) {
        set(tournamentsKey(bk, sportId), data, props.getTournamentsTtl());
    }

    // ── Matches ───────────────────────────────────────────────────────────────

    public Optional<List<ParsedMatchDto>> getMatches(BookmakerType bk, String tournamentId) {
        return get(matchesKey(bk, tournamentId), new TypeReference<>() {});
    }

    public void setMatches(BookmakerType bk, String tournamentId, List<ParsedMatchDto> data) {
        set(matchesKey(bk, tournamentId), data, props.getMatchesTtl());
    }

    // ── Raw JSON (intermediate snapshots, e.g. XBet champs) ──────────────────

    public Optional<JsonNode> getJson(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readTree(json));
        } catch (Exception e) {
            log.warn("Cache read error for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void setJson(String key, JsonNode data, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(data), ttl);
        } catch (Exception e) {
            log.warn("Cache write error for key {}: {}", key, e.getMessage());
        }
    }

    // ── Distributed lock ──────────────────────────────────────────────────────

    public boolean tryLock(String suffix, Duration ttl) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent("lock:parser:" + suffix, "1", ttl));
    }

    public void releaseLock(String suffix) {
        redis.delete("lock:parser:" + suffix);
    }

    // ── Invalidation ─────────────────────────────────────────────────────────

    public void invalidate(BookmakerType bk) {
        String bkName = bk.name().toLowerCase();
        redis.delete(sportsKey(bk));
        deleteByPattern("parser:tournaments:" + bkName + ":*");
        deleteByPattern("parser:matches:" + bkName + ":*");
    }

    public void invalidateAll() {
        deleteByPattern("parser:*");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Cache read error for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void set(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache write error for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Deletes all keys matching the pattern using Redis SCAN (non-blocking).
     * Uses RedisTemplate.scan() — the high-level Spring Data Redis 3.x API —
     * instead of the raw connection-level scan which triggers a deprecation warning.
     */
    private void deleteByPattern(String pattern) {
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(200).build();
        List<String> toDelete = new ArrayList<>();
        try (var cursor = redis.scan(opts)) {
            cursor.forEachRemaining(toDelete::add);
        } catch (Exception e) {
            log.warn("deleteByPattern scan error for '{}': {}", pattern, e.getMessage());
            return;
        }
        if (!toDelete.isEmpty()) redis.delete(toDelete);
    }

    private static String sportsKey(BookmakerType bk) {
        return String.format(KEY_SPORTS, bk.name().toLowerCase());
    }

    private static String tournamentsKey(BookmakerType bk, String sportId) {
        return String.format(KEY_TOURNAMENTS, bk.name().toLowerCase(), sportId);
    }

    private static String matchesKey(BookmakerType bk, String tournamentId) {
        return String.format(KEY_MATCHES, bk.name().toLowerCase(), tournamentId);
    }
}
