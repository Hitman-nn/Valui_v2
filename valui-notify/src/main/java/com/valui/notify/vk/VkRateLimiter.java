package com.valui.notify.vk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token-counter rate limiter: 1 message per second per VK peer_id.
 * Same Lua pattern as TelegramRateLimiter.
 */
@Slf4j
@Component
public class VkRateLimiter {

    private static final String KEY_PREFIX = "rate:vk:";

    private static final RedisScript<Long> INCR_EXPIRE_SCRIPT = RedisScript.of(
            "local n = redis.call('INCR', KEYS[1]) " +
            "if n == 1 then redis.call('EXPIRE', KEYS[1], 1) end " +
            "if n <= 1 then return 0 end " +
            "local pttl = redis.call('PTTL', KEYS[1]) " +
            "return pttl > 0 and pttl or 1000",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public VkRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns 0 if permitted, or milliseconds until the window resets. */
    public long tryAcquire(long peerId) {
        Long result = redisTemplate.execute(INCR_EXPIRE_SCRIPT, List.of(KEY_PREFIX + peerId));
        long waitMs = result != null ? result : 0L;
        if (waitMs > 0) {
            log.debug("[VK-RL] peerId={} throttled, retry in {}ms", peerId, waitMs);
        }
        return waitMs;
    }
}
