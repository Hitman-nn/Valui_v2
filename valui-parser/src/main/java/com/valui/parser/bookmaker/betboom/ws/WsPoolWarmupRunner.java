package com.valui.parser.bookmaker.betboom.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsPoolWarmupRunner implements ApplicationRunner {

    private final WsClientBorrowingPool pool;
    private final WsPoolProperties props;

    @Override
    public void run(ApplicationArguments args) {
        WsPoolProperties.Warmup cfg = props.getWarmup();
        if (!cfg.isEnabled()) {
            log.info("WS warmup disabled");
            return;
        }
        int target   = Math.min(Math.max(1, cfg.getMinReady()), pool.size());
        long deadline = System.nanoTime() + cfg.getTimeout().toNanos();

        log.info("WS warmup: waiting for {} ready connections (timeout={})", target, cfg.getTimeout());

        int lastAvail = -1;
        while (System.nanoTime() < deadline) {
            int avail = pool.available();
            if (avail != lastAvail) {
                log.info("WS warmup progress: available={}/{}", avail, pool.size());
                lastAvail = avail;
            }
            if (avail >= target) {
                log.info("WS warmup finished: {} ready", avail);
                return;
            }
            sleep(Duration.ofMillis(100));
        }

        log.warn("WS warmup timed out — trying explicit borrow warmup...");
        int woke = 0;
        while (woke < target && System.nanoTime() < deadline) {
            try (WsLease lease = pool.tryBorrow(2_000)) {
                byte[] hello = lease.getClient().awaitBinary(2_000, TimeUnit.MILLISECONDS);
                if (hello != null) woke++;
            } catch (Exception ignored) {}
        }
        if (woke >= target) {
            log.info("WS warmup finished via borrow: {}", woke);
        } else {
            log.warn("WS warmup ended with {} ready (< target={}) — connections will warm up in background",
                    Math.max(pool.available(), woke), target);
        }
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
