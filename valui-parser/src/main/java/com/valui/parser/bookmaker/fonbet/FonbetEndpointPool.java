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
    // Was 3 — a real incident (27.08) showed the pool sit at a steady ~13/200 alive for over
    // 20 hours (nothing refreshes the other 187 unless alive drops below this threshold or the
    // Tuesday weekly rescan runs) before ALL ~13 failed within moments of each other, collapsing
    // straight to 0/200 with zero warning — the emergency rescan only ever fires once the damage
    // is already total. Raising the trigger to 10 gives a margin below the observed steady state
    // so a genuine decline (13 → 10) reschedules a rescan before it can reach 13 → 0.
    private static final int    MIN_ALIVE      = 10;
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

    // markFailure() only logs at DEBUG per-endpoint and the emergency-rescan WARN only fires
    // once alive drops below MIN_ALIVE (3) — with 200 mirrors, a slow decline from 190 to 10
    // alive would otherwise be completely invisible in prod (com.valui is INFO there) until it
    // crossed that final cliff edge. This gives a periodic checkpoint of the trend.
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS, initialDelay = 1)
    void logHealth() {
        int alive = aliveCount(), total = totalCount();
        if (alive < total / 2) {
            log.warn("[FonbetPool] health: {}/{} alive — degrading", alive, total);
        } else {
            log.info("[FonbetPool] health: {}/{} alive", alive, total);
        }
    }

    /**
     * Proactive counterpart to {@link #checkAliveAndRescan()} — that method is otherwise only
     * ever called from {@link #markFailure}, i.e. only when the mirror CURRENTLY being used
     * fails. A real incident (27.08) plus days of steady-state logs afterward (27.09: pool sat at
     * 1/200 alive for 15+ straight hours, zero emergency rescans) showed the gap this leaves: as
     * long as whichever single mirror {@link #getBestEndpoint()} keeps returning happens to still
     * work, {@code markFailure} never fires — so the rescan trigger never even gets evaluated,
     * no matter how far the other 199 mirrors have silently rotted, right up until that one
     * surviving mirror itself fails and the pool free-falls to 0/200 with no warning (exactly
     * the 27.08 incident). Checking independently of any failure, every 5 minutes — cheap (one
     * Redis ZCOUNT) — closes that gap: a critically low alive count now gets noticed and
     * rescanned well before the last mirror standing has a chance to take the whole pool down
     * with it.
     */
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES, initialDelay = 5)
    void proactiveHealthCheck() {
        // 28.09 incident: checkAliveAndRescan()'s Redis call below is NOT wrapped like its
        // aliveCount()/totalCount() siblings are — some exception there (root cause still
        // unknown; masked every single time by an unrelated, separate classloader defect that
        // crashes Spring's default @Scheduled error handler the moment it tries to log a raw
        // Throwable — see e.g. Lettuce's own Netty event-loop threads hitting the identical
        // NoClassDefFoundError: ch.qos.logback.classic.spi.ThrowableProxy) went uncaught on
        // EVERY 5-minute tick for ~2h straight, silently disabling this whole safety net while
        // Fonbet's mirror pool kept degrading underneath it with zero visible warning — exactly
        // the failure mode this method exists to prevent. Catching Throwable (not just
        // Exception) here, and logging the message as a plain string rather than passing the
        // Throwable object to the logger, guarantees this task can never again go uncaught AND
        // sidesteps the ThrowableProxy construction that was hiding the real cause — so if this
        // ever fires again, the actual error message will finally be visible in prod.
        try {
            checkAliveAndRescan();
        } catch (Throwable t) {
            log.warn("[FonbetPool] proactiveHealthCheck failed: {}: {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    // ── public API ─────────────────────────────────────────────────────────────

    /** Returns the URL with the highest score (most recently confirmed alive). */
    public String getBestEndpoint() {
        Set<String> best = redis.opsForZSet().reverseRange(ZSET_KEY, 0, 0);
        return (best == null || best.isEmpty()) ? FALLBACK : best.iterator().next();
    }

    /** Endpoints with score > 0 are alive (probed successfully). Uses +inf consistent with checkAliveAndRescan(). */
    public int aliveCount() {
        try {
            Long n = redis.opsForZSet().count(ZSET_KEY, 1.0, Double.POSITIVE_INFINITY);
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
        log.debug("[FonbetPool] endpoint marked dead: {}", url);
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
        log.warn("[FonbetPool] Alive mirrors low ({}/{}), starting emergency rescan", aliveCount, totalCount());
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
