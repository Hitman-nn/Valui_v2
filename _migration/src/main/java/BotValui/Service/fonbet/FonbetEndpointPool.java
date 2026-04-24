package BotValui.Service.fonbet;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Пул зеркал Fonbet: bootstrap живых, выбор UP, маркеры успех/ошибка.
 */
@Slf4j
public class FonbetEndpointPool {
    public static final int MIN_HEALTHY = 3;

    @Getter @ToString
    public static class Endpoint {
        public final String baseUrl;
        volatile boolean up = false;
        volatile long lastSuccessMs = 0;
        volatile long lastFailMs = 0;
        volatile int consecutiveFails = 0;
        volatile long lastLatencyMs = 0;
        Endpoint(String baseUrl) { this.baseUrl = baseUrl; }
    }

    private final List<Endpoint> endpoints;
    private final AtomicInteger rr = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public FonbetEndpointPool(String pathAndQuery) {
        List<Endpoint> list = new ArrayList<>(200);
        for (int i = 1; i <= 100; i++) {
            String idx = String.format("%02d", i);
            list.add(new Endpoint("https://line" + idx + "w.bk6bba-resources.com" + pathAndQuery));
            list.add(new Endpoint("https://line" + idx + "w.bk6bba-cf-resources.com" + pathAndQuery));
        }
        Collections.shuffle(list);
        this.endpoints = Collections.unmodifiableList(list);
    }

    /** Первичная инициализация пула — ищем первые N живых зеркал, логируем статистику */
    public List<Endpoint> bootstrap(int wantAlive, int concurrency, int connectTimeoutMs, int readTimeoutMs) {
        long tStart = System.currentTimeMillis();
        List<Endpoint> shuffled = new ArrayList<>(endpoints);
        Collections.shuffle(shuffled);

        ExecutorService es = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "fonbet-bootstrap"); t.setDaemon(true); return t;
        });
        CompletionService<ProbeStat> cs = new ExecutorCompletionService<>(es);

        //final int submitN = Math.min(shuffled.size(), Math.max(wantAlive * 3, wantAlive + 10));
        final int submitN = shuffled.size(); // проверяем все зеркала (≈200)
        for (int i = 0; i < submitN; i++) {
            Endpoint ep = shuffled.get(i);
            cs.submit(() -> probeLightStat(ep, connectTimeoutMs, readTimeoutMs));
        }

        int completed = 0;
        int okCount = 0;
        long totalLatency = 0, minLatency = Long.MAX_VALUE, maxLatency = 0;
        List<String> aliveUrls = new ArrayList<>();

        try {
            while (completed < submitN && okCount < wantAlive) {
                Future<ProbeStat> fut = cs.poll(4, TimeUnit.SECONDS);
                if (fut == null) break;
                completed++;
                ProbeStat ps = fut.get();
                if (ps != null && ps.ok) {
                    okCount++;
                    totalLatency += ps.latency;
                    minLatency = Math.min(minLatency, ps.latency);
                    maxLatency = Math.max(maxLatency, ps.latency);
                    aliveUrls.add(ps.url);
                }
            }
        } catch (Exception ignore) {
        } finally {
            es.shutdownNow();
        }

        long elapsed = System.currentTimeMillis() - tStart;
        if (okCount > 0) {
            double avgLatency = totalLatency / (double) okCount;
            log.info("[FonbetBootstrap] Проверено: {}, успешных: {}, средняя задержка: {} ms (min={} / max={}), время: {} ms",
                    completed, okCount, String.format("%.1f", avgLatency), minLatency, maxLatency, elapsed);
            log.info("[FonbetBootstrap] Примеры рабочих зеркал: {}", String.join(", ", aliveUrls.stream().limit(5).toList()));
        } else {
            log.warn("[FonbetBootstrap] Не найдено живых зеркал (проверено {} за {} ms)", completed, elapsed);
        }

        return endpoints.stream().filter(e -> e.up).limit(wantAlive).toList();
    }

    private record ProbeStat(boolean ok, String url, long latency) {}

    private ProbeStat probeLightStat(Endpoint ep, int cto, int rto) {
        var r = HttpProbe.probe200(ep.baseUrl, cto, rto, 2);
        if (r.isUp()) {
            markSuccess(ep, r.getLatencyMs());
            return new ProbeStat(true, ep.baseUrl, r.getLatencyMs());
        } else {
            markFailure(ep);
            return new ProbeStat(false, ep.baseUrl, 0);
        }
    }

    public Endpoint selectUp() {
        lock.readLock().lock();
        try {
            int n = endpoints.size();
            if (n == 0) return null;
            int start = Math.floorMod(rr.getAndIncrement(), n);
            for (int i = 0; i < n; i++) {
                Endpoint e = endpoints.get((start + i) % n);
                if (e.up) return e;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void markSuccess(Endpoint e, long latencyMs) {
        lock.writeLock().lock();
        try {
            e.up = true;
            e.lastLatencyMs = latencyMs;
            e.lastSuccessMs = System.currentTimeMillis();
            e.consecutiveFails = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void markFailure(Endpoint e) {
        lock.writeLock().lock();
        try {
            e.up = false;
            e.lastFailMs = System.currentTimeMillis();
            e.consecutiveFails++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int countHealthy() {
        lock.readLock().lock();
        try {
            int ok = 0;
            for (Endpoint e : endpoints) if (e.up) ok++;
            return ok;
        } finally {
            lock.readLock().unlock();
        }
    }

    public synchronized void fullRescan() {
        log.info("[FonbetPool] Starting full rescan of all endpoints...");
        bootstrapAll(/*concurrency*/ 16, /*cto*/ 3000, /*rto*/ 5000);
        log.info("[FonbetPool] Rescan completed. Healthy: {}", countHealthy());
    }

    // Полный рескан: обрабатываем ВСЕ результаты, а не до wantAlive
    private void bootstrapAll(int concurrency, int connectTimeoutMs, int readTimeoutMs) {
        long tStart = System.currentTimeMillis();
        List<Endpoint> shuffled = new ArrayList<>(endpoints);
        Collections.shuffle(shuffled);

        ExecutorService es = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "fonbet-rescan"); t.setDaemon(true); return t;
        });
        CompletionService<ProbeStat> cs = new ExecutorCompletionService<>(es);

        for (Endpoint ep : shuffled) {
            cs.submit(() -> probeLightStat(ep, connectTimeoutMs, readTimeoutMs));
        }

        int completed = 0, okCount = 0;
        long totalLatency = 0, minLatency = Long.MAX_VALUE, maxLatency = 0;
        final int submitN = shuffled.size();

        try {
            while (completed < submitN) {               // <— ключевая разница
                Future<ProbeStat> fut = cs.poll(6, TimeUnit.SECONDS);
                if (fut == null) break;
                completed++;
                ProbeStat ps = fut.get();
                if (ps != null && ps.ok) {
                    okCount++;
                    totalLatency += ps.latency;
                    minLatency = Math.min(minLatency, ps.latency);
                    maxLatency = Math.max(maxLatency, ps.latency);
                }
            }
        } catch (Exception ignore) {
        } finally {
            es.shutdownNow();
        }

        long elapsed = System.currentTimeMillis() - tStart;
        if (okCount > 0) {
            double avg = totalLatency / (double) okCount;
            log.info("[FonbetBootstrap] FULL: checked={}, up={}, avgLatency={} ms (min={} / max={}), time={} ms",
                    completed, okCount, String.format("%.1f", avg), minLatency, maxLatency, elapsed);
        } else {
            log.warn("[FonbetBootstrap] FULL: no alive endpoints (checked {} in {} ms)", completed, elapsed);
        }
    }
}
