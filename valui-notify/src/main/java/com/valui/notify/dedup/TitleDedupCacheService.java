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

    // Short TTL for the in-flight "claim" placeholder written by tryClaim() — deliberately much
    // shorter than the real dedup-ttl-minutes. It only needs to cover the realistic round-trip of
    // one notification send (Kafka publish → NotificationDispatcher consume → Telegram API call →
    // store()), typically well under a few seconds. Keeping it short bounds the worst case if that
    // pipeline stalls or fails entirely: the placeholder expires and a later duplicate event can
    // still send normally, instead of silently suppressing that match's notifications for the
    // whole dedup window (which an unbounded/long claim TTL would risk turning "occasional
    // duplicate" into "occasional permanent loss" — a strictly worse failure mode).
    private static final Duration CLAIM_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Value("${valui.notifications.dedup-ttl-minutes:180}")
    private int dedupTtlMinutes;

    /**
     * Computes the dedup key for an event.
     *
     * @param chatId      target chat ID (scope per subscriber)
     * @param bookmaker   bookmaker name (BETBOOM, FONBET, etc.)
     * @param url         full event URL (last path segment stripped to get the tournament base)
     * @param title       raw event title from the parser
     * @param startEpoch  Unix timestamp of match start time; 0 if unavailable.
     *                    Included in the hash so two matches with the same title but different
     *                    start times (e.g. same players in different rounds) get distinct keys.
     */
    public String computeKey(long chatId, String bookmaker, String url, String title, long startEpoch) {
        String urlBase    = extractUrlBase(url);
        String epochPart  = startEpoch > 0 ? ":" + startEpoch : "";
        String input      = bookmaker + ":" + urlBase + ":" + (title == null ? "" : title.strip().toLowerCase()) + epochPart;
        String hash       = sha256Hex(input);
        return KEY_PREFIX + chatId + ":" + hash;
    }

    public Optional<TitleDedupEntry> find(String dedupKey) {
        try {
            String json = redisTemplate.opsForValue().get(dedupKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TitleDedupEntry.class));
        } catch (Exception e) {
            log.warn("[DEDUP] Failed to read entry for key={}: {}", dedupKey, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Atomically claims {@code dedupKey} for a first-send in progress, closing the TOCTOU window
     * between a caller's {@link #find()} miss and its own subsequent {@link #store()}: without
     * this, two events detected for the same match under different external IDs (e.g. BetBoom
     * pre-match → live transition) within milliseconds of each other could both see no existing
     * entry and both proceed to send a brand-new Telegram message instead of one sending and the
     * other editing.
     *
     * <p>Writes a placeholder entry ({@code telegramMessageId=null}) via Redis {@code SETNX}
     * (only succeeds if the key doesn't already exist) with {@link #CLAIM_TTL}. The real caller
     * that wins the claim is expected to overwrite it via {@link #store} with the real entry
     * once the message is actually sent. A caller that loses the claim should re-{@link #find}:
     * a placeholder (still {@code telegramMessageId=null}) means the winner hasn't finished
     * sending yet — suppress; a populated entry means it already has a real message to edit.
     *
     * @return {@code true} if this call won the claim (proceed with first-send),
     *         {@code false} if an entry (placeholder or real) already existed.
     */
    public boolean tryClaim(String dedupKey, Long chatId) {
        try {
            TitleDedupEntry placeholder = new TitleDedupEntry(null, chatId, null, null);
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                    dedupKey, objectMapper.writeValueAsString(placeholder), CLAIM_TTL);
            return Boolean.TRUE.equals(claimed);
        } catch (JsonProcessingException e) {
            log.warn("[DEDUP] Failed to serialize claim placeholder for key={}: {}", dedupKey, e.getMessage(), e);
            // Serialization can't fail for this fixed placeholder shape in practice, but if it
            // somehow did, treat it as "did not claim" — the caller's safe fallback is to suppress
            // rather than risk a duplicate send.
            return false;
        }
    }

    public void store(String dedupKey, TitleDedupEntry entry) {
        store(dedupKey, entry, Duration.ofMinutes(dedupTtlMinutes));
    }

    public void store(String dedupKey, TitleDedupEntry entry, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(
                    dedupKey,
                    objectMapper.writeValueAsString(entry),
                    ttl);
        } catch (JsonProcessingException e) {
            log.warn("[DEDUP] Failed to store entry for key={}: {}", dedupKey, e.getMessage(), e);
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
