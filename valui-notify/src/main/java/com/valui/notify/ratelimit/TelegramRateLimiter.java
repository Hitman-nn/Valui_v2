package com.valui.notify.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token-counter rate limiter: 1 message per second per Telegram chat ID.
 *
 * Uses Redis INCR + EXPIRE (1 s TTL). The first increment within a new window
 * sets the expiry; subsequent calls within the same second are rejected.
 * This is a fixed-window approach — suitable for Telegram's per-chat limit.
 */
@Component
@RequiredArgsConstructor
public class TelegramRateLimiter {

    private static final String KEY_PREFIX = "rate:tg:";
    private static final long LIMIT = 1L;

    private final StringRedisTemplate redisTemplate;

    /**
     * Returns {@code true} if the send is permitted.
     * The first call within each 1-second window returns true; subsequent calls return false.
     */
    public boolean tryAcquire(long chatId) {
        String key = KEY_PREFIX + chatId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(1));
        }
        return count != null && count <= LIMIT;
    }
}
