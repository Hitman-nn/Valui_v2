package com.valui.betting.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis store for match data attached to "💸 Поставил" notification buttons.
 *
 * Key: {@code bet:notif:{notificationLogId}}
 * TTL: 30 days (matching notification history lifetime in Telegram chats).
 *
 * Written by {@code SportEventConsumer} (valui-notify) for all events.
 * Read by {@code BetNotifCallback} (valui-bot) when the user taps the button.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BetNotifCacheService {

    static final String KEY_PREFIX = "bet:notif:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void store(String notifLogId, BetNotifData data) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + notifLogId,
                    objectMapper.writeValueAsString(data), TTL);
        } catch (JsonProcessingException e) {
            log.error("[BET-NOTIF] Failed to cache data for notifLogId={}: {}", notifLogId, e.getMessage());
        }
    }

    public Optional<BetNotifData> find(String notifLogId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + notifLogId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, BetNotifData.class));
        } catch (Exception e) {
            log.error("[BET-NOTIF] Failed to read data for notifLogId={}: {}", notifLogId, e.getMessage());
            return Optional.empty();
        }
    }
}
