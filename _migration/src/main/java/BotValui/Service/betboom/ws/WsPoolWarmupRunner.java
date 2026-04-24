package BotValui.Service.betboom.ws;

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
        var cfg = props.getWarmup();
        if (!cfg.isEnabled()) {
            log.info("WS warmup disabled");
            return;
        }
        final int target = Math.min(Math.max(1, cfg.getMinReady()), pool.size());
        final long deadline = System.nanoTime() + cfg.getTimeout().toNanos();

        log.info("WS warmup: waiting for {} ready connections (timeout={})", target, cfg.getTimeout());

        // Пробуем дождаться готовых соединений, проверяя доступные лизы (free queue)
        int lastAvail = -1;
        while (System.nanoTime() < deadline) {
            int avail = pool.available();
            if (avail != lastAvail) {
                log.info("WS warmup progress: available={}/{}", avail, pool.size());
                lastAvail = avail;
            }
            if (avail >= target) {
                log.info("WS warmup finished: {} connections are ready", avail);
                return;
            }
            sleepSilently(Duration.ofMillis(100));
        }

        // Фоллбек: принудительно «пробуждаем» соединения borrow→await HELLO→release
        log.warn("WS warmup timed out by availability. Trying explicit borrow-help warmup…");
        int woke = 0;
        while (woke < target && System.nanoTime() < deadline) {
            try (WsLease lease = pool.tryBorrow(2_000)) {
                // ждём первый бинарный кадр у конкретного клиента (HELLO)
                byte[] hello = lease.getClient().awaitBinary(2_000, TimeUnit.MILLISECONDS);
                if (hello != null) woke++;
            } catch (Exception ignored) { /* слот ещё не готов — идём дальше */ }
        }
        if (woke >= target) {
            log.info("WS warmup finished via explicit borrow: {}", woke);
        } else {
            log.warn("WS warmup ended with {} ready (< target={}) — продолжим запуск, соединения догреются в фоне.",
                    Math.max(pool.available(), woke), target);
        }
    }

    private static void sleepSilently(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
