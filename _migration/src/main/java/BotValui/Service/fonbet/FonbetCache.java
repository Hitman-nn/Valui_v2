package BotValui.Service.fonbet;

import BotValui.Service.Parser;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Кэш-снимок Fonbet API: обновляется каждые N секунд, использует пул зеркал */
@Slf4j
public class FonbetCache {
    private static final String PATH_AND_QUERY = "/events/list?lang=ru&scopeMarket=1600";
    private static final long REFRESH_INTERVAL_SEC = 30;
    private static final long RESCAN_COOLDOWN_MS = 10 * 60 * 1000;

    private static final AtomicReference<JSONObject> snapshot = new AtomicReference<>(null);
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fonbet-cache-refresh");
                t.setDaemon(true);
                return t;
            });

    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);
    private static volatile long lastSuccessTime = 0;
    private static volatile long lastFailTime = 0;

    private static final FonbetEndpointPool POOL = new FonbetEndpointPool(PATH_AND_QUERY);

    private static final AtomicLong lastRescanTime = new AtomicLong(0);

    public static void start() {
        log.info("Starting Fonbet cache refresher ({} sec)", REFRESH_INTERVAL_SEC);

        // 1) быстрый bootstrap рабочих зеркал
        List<FonbetEndpointPool.Endpoint> alive = POOL.bootstrap(/*wantAlive*/ 10, /*threads*/ 8, /*cto*/ 3000, /*rto*/ 5000);
        log.info("Fonbet bootstrap: UP={} (из ~200), healthyNow={}", alive.size(), POOL.countHealthy());

        // 2) первый fetch + периодический рефреш
        refreshSafe();
        scheduler.scheduleWithFixedDelay(FonbetCache::refreshSafe, REFRESH_INTERVAL_SEC, REFRESH_INTERVAL_SEC, TimeUnit.SECONDS);

        // Плановое обновление — каждый вторник в 03:00
        scheduleWeeklyRescan(DayOfWeek.TUESDAY, LocalTime.of(3, 0));
    }

    private static void scheduleWeeklyRescan(DayOfWeek dayOfWeek, LocalTime time) {
        scheduler.submit(() -> {
            long delay = computeInitialDelay(dayOfWeek, time);
            log.info("[FonbetCache] Scheduled weekly rescan: every {} at {} (first in {} sec)",
                    dayOfWeek, time, delay / 1000);

            scheduler.scheduleAtFixedRate(() -> {
                triggerRescan("weekly-schedule");
            }, delay, TimeUnit.DAYS.toMillis(7), TimeUnit.MILLISECONDS);
        });
    }

    private static synchronized void triggerRescan(String reason) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRescanTime.get();
        if (elapsed < RESCAN_COOLDOWN_MS) {
            log.warn("[FonbetCache] Rescan skipped (cooldown active, {} sec remaining)", (RESCAN_COOLDOWN_MS - elapsed) / 1000);
            return;
        }
        lastRescanTime.set(now);

        scheduler.submit(() -> {
            try {
                log.info("[FonbetCache] Triggering full rescan due to {}", reason);
                POOL.fullRescan();
            } catch (Throwable t) {
                log.error("[FonbetCache] Rescan failed: {}", t.toString());
            }
        });
    }

    private static long computeInitialDelay(DayOfWeek dayOfWeek, LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime next = now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time);
        if (next.isBefore(now)) {
            next = next.plusWeeks(1);
        }
        return Duration.between(now, next).toMillis();
    }

    public static JSONObject getSnapshot() { return snapshot.get(); }

    private static void refreshSafe() {
        FonbetEndpointPool.Endpoint ep = POOL.selectUp();
        if (ep == null) {
            log.warn("No UP endpoints in pool — triggering emergency rescan...");
            triggerRescan("no-up");
            return;
        }

        try {
            long t0 = System.nanoTime();
            Object obj = Parser.getJSONObject(ep.getBaseUrl(), null); // ВАЖНО: в Parser должен быть потоковый парсинг из Reader!
            long durMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);

            if (obj instanceof JSONObject json) {
                snapshot.set(json);
                POOL.markSuccess(ep, durMs);

                int ok = successCount.incrementAndGet();
                lastSuccessTime = System.currentTimeMillis();
                log.debug("Fonbet cache refresh OK via {} ({} ms) [ok={} fail={} healthy={}]",
                        ep.getBaseUrl(), durMs, ok, failCount.get(), POOL.countHealthy());

                if (ok % 10 == 0) {
                    log.info("[FonbetCache] OK={}, FAIL={}, lastSuccess={}, lastFail={}, healthy={}",
                            ok, failCount.get(),
                            Instant.ofEpochMilli(lastSuccessTime),
                            (lastFailTime == 0 ? "never" : Instant.ofEpochMilli(lastFailTime)),
                            POOL.countHealthy());
                }
            } else {
                POOL.markFailure(ep);
                handleFailure("Unexpected response type: " + obj.getClass());
                checkAndRescan();
            }
        } catch (IOException | ParseException e) {
            POOL.markFailure(ep);
            handleFailure("IO/Parse error: " + e);
            checkAndRescan();
        } catch (Throwable t) {
            POOL.markFailure(ep);
            handleFailure("Fatal error: " + t);
            checkAndRescan();
        }
    }

    private static void handleFailure(String reason) {
        int fails = failCount.incrementAndGet();
        lastFailTime = System.currentTimeMillis();
        log.warn("Fonbet cache refresh failed (#{}) - {}", fails, reason);

        if (fails % 5 == 0) {
            log.warn("[FonbetCache] OK={}, FAIL={}, lastSuccess={}, lastFail={}, healthy={}",
                    successCount.get(), fails,
                    (lastSuccessTime == 0 ? "never" : Instant.ofEpochMilli(lastSuccessTime)),
                    Instant.ofEpochMilli(lastFailTime),
                    POOL.countHealthy());
        }
    }

    private static void checkAndRescan() {
        int healthy = POOL.countHealthy();
        if (healthy < FonbetEndpointPool.MIN_HEALTHY) {
            triggerRescan("low-healthy=" + healthy);
        }
    }

    public static void stop() {
        log.info("Stopping Fonbet cache refresher...");
        scheduler.shutdownNow();
        log.info("[FonbetCache] Final stats: OK={}, FAIL={}, lastSuccess={}, lastFail={}, healthy={}",
                successCount.get(), failCount.get(),
                (lastSuccessTime == 0 ? "never" : Instant.ofEpochMilli(lastSuccessTime)),
                (lastFailTime == 0 ? "never" : Instant.ofEpochMilli(lastFailTime)),
                POOL.countHealthy());
    }
}
