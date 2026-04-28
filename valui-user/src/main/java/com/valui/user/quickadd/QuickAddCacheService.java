package com.valui.user.quickadd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed store for "quick-add controller" data attached to notification buttons.
 *
 * Key scheme: {@code qadd:{notificationLogId}}
 * TTL: 30 days — notifications live in chat history indefinitely,
 *       so the button must remain functional long after delivery.
 *
 * Written by {@code SportEventConsumer} (valui-notify) when a SPORT-type controller
 * detects a new tournament. Read by {@code QuickAddControllerCallback} (valui-bot)
 * when the user taps "➕ Следить за турниром".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickAddCacheService {

    static final String KEY_PREFIX = "qadd:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void store(String key, QuickAddData data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(KEY_PREFIX + key, json, TTL);
            log.debug("[QUICK-ADD] Stored key={} bookmaker={}", key, data.bookmaker());
        } catch (JsonProcessingException e) {
            log.error("[QUICK-ADD] Failed to serialize data for key={}: {}", key, e.getMessage());
        }
    }

    public Optional<QuickAddData> find(String key) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, QuickAddData.class));
        } catch (Exception e) {
            log.error("[QUICK-ADD] Failed to read data for key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
