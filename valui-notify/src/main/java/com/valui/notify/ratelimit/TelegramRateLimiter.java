package com.valui.notify.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token-counter rate limiter: 1 message per second per Telegram chat ID.
 *
 * Uses an atomic Lua script: INCR + EXPIRE in a single round-trip.
 * Separating INCR and EXPIRE into two commands creates a TOCTOU race where
 * the key can be incremented without a TTL, leaking it permanently.
 */
@Component
public class TelegramRateLimiter {

    private static final String KEY_PREFIX = "rate:tg:";
    private static final long   LIMIT      = 1L;

    // Atomic: increment counter, set 1 s TTL on first call, return new value.
    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) " +
            "if n == 1 then redis.call('EXPIRE', KEYS[1], 1) end " +
            "return n",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public TelegramRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns {@code true} if the send is permitted.
     * The first call within each 1-second window returns true; subsequent calls return false.
     */
    public boolean tryAcquire(long chatId) {
        String key   = KEY_PREFIX + chatId;
        Long   count = redisTemplate.execute(INCR_EXPIRE_SCRIPT, List.of(key));
        return count != null && count <= LIMIT;
    }
}
