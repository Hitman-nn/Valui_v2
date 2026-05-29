package com.valui.parser.bookmaker.fonbet;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Redis ZSet-backed pool of Fonbet mirror URLs.
 * Score = last successful timestamp (ms); higher = more recently alive = preferred.
 * Score = 0.0 means dead/unknown.
 *
 * On startup probes all 200 mirrors in background (does not block Spring context).
 * Weekly rescan every Tuesday at 03:00 Moscow time.
 * Emergency rescan when alive count drops below MIN_ALIVE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FonbetEndpointPool {

    static final String ZSET_KEY = "fonbet:endpoints";
    static final String PATH     = "/events/list?lang=ru&scopeMarket=1600";

    private static final String FALLBACK       = "https://line32w.bk6bba-resources.com" + PATH;
    private static final int    MIN_ALIVE      = 3;
    private static final long   RESCAN_COOLDOWN_MS = 10 * 60_000L;
    private static final int    PROBE_CONNECT_MS   = 3_000;
    private static final int    PROBE_READ_MS      = 5_000;

    private final StringRedisTemplate redis;
    private final AtomicLong lastRescanMs = new AtomicLong(0);

    @PostConstruct
    void init() {
        Long count = redis.opsForZSet().zCard(ZSET_KEY);
        if (count == null || count == 0) {
            seed();
        }
        // Probe all mirrors in background — don't hold up Spring context startup
        Thread t = new Thread(() -> scanEndpoints("bootstrap", 8), "fonbet-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "0 0 3 * * TUE", zone = "Europe/Moscow")
    void weeklyRescan() {
        log.info("[FonbetPool] Weekly rescan started");
        scanEndpoints("weekly-rescan", 16);
    }

    // ── public API ─────────────────────────────────────────────────────────────

    /** Returns the URL with the highest score (most recently confirmed alive). */
    public String getBestEndpoint() {
        Set<String> best = redis.opsForZSet().reverseRange(ZSET_KEY, 0, 0);
        return (best == null || best.isEmpty()) ? FALLBACK : best.iterator().next();
    }

    /** Endpoints with score > 0 are alive (probed successfully). */
    public int aliveCount() {
        try {
            Long n = redis.opsForZSet().count(ZSET_KEY, 1.0, Double.MAX_VALUE);
            return n != null ? n.intValue() : 0;
        } catch (Exception e) { return 0; }
    }

    public int totalCount() {
        try {
            Long n = redis.opsForZSet().zCard(ZSET_KEY);
            return n != null ? n.intValue() : 0;
        } catch (Exception e) { return 0; }
    }

    public void markSuccess(String url) {
        redis.opsForZSet().add(ZSET_KEY, url, (double) System.currentTimeMillis());
    }

    public void markFailure(String url) {
        redis.opsForZSet().add(ZSET_KEY, url, 0.0);
        checkAliveAndRescan();
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void seed() {
        log.info("[FonbetPool] Seeding 200 mirror URLs into Redis...");
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>(202);
        for (int i = 1; i <= 100; i++) {
            String idx = String.format("%02d", i);
            tuples.add(ZSetOperations.TypedTuple.of("https://line" + idx + "w.bk6bba-resources.com"    + PATH, 0.0));
            tuples.add(ZSetOperations.TypedTuple.of("https://line" + idx + "w.bk6bba-cf-resources.com" + PATH, 0.0));
        }
        tuples.add(ZSetOperations.TypedTuple.of(FALLBACK, (double) System.currentTimeMillis()));
        redis.delete(ZSET_KEY);
        redis.opsForZSet().add(ZSET_KEY, tuples);
        log.info("[FonbetPool] Seed complete.");
    }

    /**
     * Probes every URL in the pool concurrently using lightweight HEAD requests
     * (same approach as V1 FonbetEndpointPool.bootstrap). Updates Redis scores.
     */
    private void scanEndpoints(String reason, int concurrency) {
        Set<String> all = redis.opsForZSet().range(ZSET_KEY, 0, -1);
        if (all == null || all.isEmpty()) {
            log.warn("[FonbetPool] {} — pool is empty, re-seeding", reason);
            seed();
            return;
        }

        List<String> urls = new ArrayList<>(all);
        Collections.shuffle(urls);

        ExecutorService ex = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "fonbet-probe");
            t.setDaemon(true);
            return t;
        });
        CompletionService<ProbeResult> cs = new ExecutorCompletionService<>(ex);
        for (String url : urls) cs.submit(() -> probe(url));

        int alive = 0, done = 0;
        long t0 = System.currentTimeMillis();
        try {
            while (done < urls.size()) {
                Future<ProbeResult> f = cs.poll(6, TimeUnit.SECONDS);
                if (f == null) break;
                done++;
                ProbeResult r = f.get();
                if (r.ok) {
                    redis.opsForZSet().add(ZSET_KEY, r.url, (double) System.currentTimeMillis());
                    alive++;
                } else {
                    redis.opsForZSet().add(ZSET_KEY, r.url, 0.0);
                }
            }
        } catch (Exception e) {
            log.warn("[FonbetPool] {} probe interrupted: {}", reason, e.getMessage());
        } finally {
            ex.shutdownNow();
        }
        log.info("[FonbetPool] {} done: alive={}/{} checked in {} ms", reason, alive, done,
                System.currentTimeMillis() - t0);
    }

    private void checkAliveAndRescan() {
        Long alive = redis.opsForZSet().count(ZSET_KEY, 1.0, Double.POSITIVE_INFINITY);
        if (alive != null && alive < MIN_ALIVE) {
            triggerEmergencyRescan(alive);
        }
    }

    private void triggerEmergencyRescan(long aliveCount) {
        long now = System.currentTimeMillis();
        long last = lastRescanMs.get();
        if (now - last < RESCAN_COOLDOWN_MS) return;
        if (!lastRescanMs.compareAndSet(last, now)) return;
        log.warn("[FonbetPool] Alive mirrors low ({}), starting emergency rescan", aliveCount);
        Thread t = new Thread(() -> scanEndpoints("emergency-rescan", 16), "fonbet-emergency-rescan");
        t.setDaemon(true);
        t.start();
    }

    /** Lightweight HEAD probe; falls back to GET+Range if HEAD returns 405. */
    private ProbeResult probe(String url) {
        long t0 = System.nanoTime();
        try {
            int code;
            HttpURLConnection c = openConn(url, "HEAD");
            try {
                code = c.getResponseCode();
            } finally {
                c.disconnect();
            }
            if (code == 405) {
                HttpURLConnection cGet = openConn(url, "GET");
                cGet.setRequestProperty("Range", "bytes=0-0");
                try {
                    code = cGet.getResponseCode();
                } finally {
                    cGet.disconnect();
                }
                if (code == 206) code = 200;
            }
            return new ProbeResult(url, code == 200, (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            return new ProbeResult(url, false, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private HttpURLConnection openConn(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(PROBE_CONNECT_MS);
        c.setReadTimeout(PROBE_READ_MS);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private record ProbeResult(String url, boolean ok, long latencyMs) {}
}
