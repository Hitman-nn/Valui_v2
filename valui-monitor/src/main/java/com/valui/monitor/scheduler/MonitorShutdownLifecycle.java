package com.valui.monitor.scheduler;

import com.valui.monitor.scheduler.drr.DrrDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Stops the polling pipeline at the START of shutdown, before infrastructure goes away.
 *
 * <p>01.10 prod: every deploy logged ~50 ERROR + ~100 WARN + ~1200 stack-trace lines in 2.5s
 * ({@code Event persistence failed: LettuceConnectionFactory is STOPPING}, {@code [DRR] Unhandled
 * exception in task}, {@code [SchedulerState] save failed}). Cause: {@link DrrDispatcher} only
 * stopped in {@code @PreDestroy}, i.e. in the bean-destruction step — but
 * {@code LettuceConnectionFactory} is itself a {@link SmartLifecycle} (phase 0) and is stopped in
 * the lifecycle step that runs BEFORE any bean is destroyed. In-flight controller tasks then hit a
 * closed Redis. SmartLifecycle beans stop in descending phase order, so a high phase here stops
 * dispatching and drains running tasks while Redis, Kafka and the DB are all still available.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorShutdownLifecycle implements SmartLifecycle {

    /** Above Lettuce (0) and below Spring Kafka listener containers / web-server graceful shutdown. */
    static final int PHASE = Integer.MAX_VALUE - 4096;

    private final DrrDispatcher dispatcher;

    private volatile boolean running;

    @Override
    public void start() {
        // DrrDispatcher itself is started by MonitorScheduler's @PostConstruct (it needs the
        // per-bookmaker controller counts) — this bean only owns the shutdown ordering.
        running = true;
    }

    @Override
    public void stop() {
        log.info("[SHUTDOWN] Stopping monitor dispatch before Redis/Kafka shut down");
        dispatcher.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
