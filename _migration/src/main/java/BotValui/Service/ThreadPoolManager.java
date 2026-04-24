package BotValui.Service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Getter
@Slf4j
public class ThreadPoolManager {
    private final static int THREAD_POOL_SIZE = 35;
    private final static int TIMEOUT_WAIT_SHUTDOWN_SECONDS = 90;

    private final ScheduledExecutorService executor;
    private static volatile ThreadPoolManager instance;

    private ThreadPoolManager() {
        this.executor = Executors.newScheduledThreadPool(THREAD_POOL_SIZE);
        log.info("ThreadPoolManager initialized with {} threads", THREAD_POOL_SIZE);
    }

    public static ThreadPoolManager getInstance() {
        if (instance == null) {
            synchronized (ThreadPoolManager.class) {
                if (instance == null) {
                    instance = new ThreadPoolManager();
                }
            }
        }
        return instance;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(TIMEOUT_WAIT_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("Thread pool did not terminate gracefully, forcing shutdown");
            }
            log.info("Thread pool successfully shutdown");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            log.error("Thread pool shutdown interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    public void shutdownNow() {
        executor.shutdownNow();
        log.warn("Thread pool shutdown immediately");
    }
}
