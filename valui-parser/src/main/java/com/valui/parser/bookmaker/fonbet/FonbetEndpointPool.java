package com.valui.parser.bookmaker.fonbet;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis ZSet-backed pool of Fonbet mirror URLs.
 * Score = last successful timestamp; higher score = more recently alive = preferred.
 * Persists across restarts — on startup, seeds 200 default mirrors if the set is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FonbetEndpointPool {

    static final String ZSET_KEY  = "fonbet:endpoints";
    static final String PATH      = "/events/list?lang=ru&scopeMarket=1600";
    private static final String FALLBACK = "https://line32w.bk6bba-resources.com" + PATH;

    private final StringRedisTemplate redis;

    @PostConstruct
    void seed() {
        Long count = redis.opsForZSet().zCard(ZSET_KEY);
        if (count == null || count == 0) {
            log.info("Seeding Fonbet endpoint pool into Redis (200 mirrors)...");
            for (int i = 1; i <= 100; i++) {
                String idx = String.format("%02d", i);
                redis.opsForZSet().add(ZSET_KEY, "https://line" + idx + "w.bk6bba-resources.com"    + PATH, 0.0);
                redis.opsForZSet().add(ZSET_KEY, "https://line" + idx + "w.bk6bba-cf-resources.com" + PATH, 0.0);
            }
            // Boost known-good mirror so reverseRange(0,0) picks it first on cold start.
            // All 200 mirrors get score=0.0 above; Redis then returns the lexicographically
            // last entry (line99w) which doesn't exist. Overwrite FALLBACK with current
            // timestamp so it wins until a real mirror is marked successful.
            redis.opsForZSet().add(ZSET_KEY, FALLBACK, (double) System.currentTimeMillis());
            log.info("Fonbet pool seeded.");
        }
    }

    /** Returns the URL with the highest score (most recently successful). */
    public String getBestEndpoint() {
        Set<String> best = redis.opsForZSet().reverseRange(ZSET_KEY, 0, 0);
        return (best == null || best.isEmpty()) ? FALLBACK : best.iterator().next();
    }

    public void markSuccess(String url) {
        redis.opsForZSet().add(ZSET_KEY, url, (double) System.currentTimeMillis());
    }

    /** Pushes the URL to the bottom of the ranking. */
    public void markFailure(String url) {
        redis.opsForZSet().add(ZSET_KEY, url, 0.0);
    }
}
