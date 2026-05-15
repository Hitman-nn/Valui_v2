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

    // Atomic: increment counter, set 1 s TTL on first call.
    // Returns 0 if the call is within the limit, else PTTL (ms until window resets).
    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) " +
            "if n == 1 then redis.call('EXPIRE', KEYS[1], 1) end " +
            "if n <= 1 then return 0 end " +
            "local pttl = redis.call('PTTL', KEYS[1]) " +
            "return pttl > 0 and pttl or 1000",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public TelegramRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns {@code 0} if the send is permitted, or milliseconds until the window resets.
     * The first call within each 1-second window returns 0; subsequent calls return PTTL.
     */
    public long tryAcquire(long chatId) {
        String key    = KEY_PREFIX + chatId;
        Long   result = redisTemplate.execute(INCR_EXPIRE_SCRIPT, List.of(key));
        return result != null ? result : 0L;
    }
}
