package com.valui.notify.dedup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis-backed title deduplication cache for notification suppression and editing.
 *
 * Key: {@code notif:title-dedup:{chatId}:{sha256(bookmaker:urlBase:title)}}
 * TTL: configurable via {@code valui.notifications.dedup-ttl-minutes} (default 60 min).
 *
 * "urlBase" = event URL with the last path segment stripped, which identifies the
 * tournament/line independently of the specific event ID. This makes deduplication
 * universal across all bookmakers without changes to the parser layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TitleDedupCacheService {

    private static final String KEY_PREFIX = "notif:title-dedup:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Value("${valui.notifications.dedup-ttl-minutes:60}")
    private int dedupTtlMinutes;

    /**
     * Computes the dedup key for an event.
     *
     * @param chatId     target chat ID (scope per subscriber)
     * @param bookmaker  bookmaker name (BETBOOM, FONBET, etc.)
     * @param url        full event URL (last path segment stripped to get the tournament base)
     * @param title      raw event title from the parser
     */
    public String computeKey(long chatId, String bookmaker, String url, String title) {
        String urlBase = extractUrlBase(url);
        String input   = bookmaker + ":" + urlBase + ":" + (title == null ? "" : title.strip().toLowerCase());
        String hash    = sha256Hex(input);
        return KEY_PREFIX + chatId + ":" + hash;
    }

    public Optional<TitleDedupEntry> find(String dedupKey) {
        try {
            String json = redisTemplate.opsForValue().get(dedupKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TitleDedupEntry.class));
        } catch (Exception e) {
            log.warn("[DEDUP] Failed to read entry for key={}: {}", dedupKey, e.getMessage());
            return Optional.empty();
        }
    }

    public void store(String dedupKey, TitleDedupEntry entry) {
        try {
            redisTemplate.opsForValue().set(
                    dedupKey,
                    objectMapper.writeValueAsString(entry),
                    Duration.ofMinutes(dedupTtlMinutes));
        } catch (JsonProcessingException e) {
            log.warn("[DEDUP] Failed to store entry for key={}: {}", dedupKey, e.getMessage());
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String extractUrlBase(String url) {
        if (url == null || url.isBlank()) return "";
        // Strip query params
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        // Strip trailing slash
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        // Strip last path segment (the event-specific ID)
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : path;
    }
}
