package com.valui.parser.bookmaker.betboom.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Binds BetBoom WS pool health to Micrometer, following the same pattern as
 * {@link com.valui.parser.health.ParserHealthService#bindMetrics()} for circuit breakers.
 *
 * {@code betboom.ws.pool.backlog} is the metric to alert on: a steadily climbing value between
 * scrapes means some connection's inbox is filling up faster than it's drained — the class of
 * leak that caused the 2026-08 OOM, now visible in Grafana instead of only in a post-mortem
 * heap dump.
 */
@Component
@RequiredArgsConstructor
public class WsPoolMetricsBinder {

    private final WsClientBorrowingPool pool;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void bindMetrics() {
        Gauge.builder("betboom.ws.pool.connected", pool, WsClientBorrowingPool::connected)
                .description("BetBoom WS slots currently connected")
                .register(meterRegistry);

        Gauge.builder("betboom.ws.pool.available", pool, WsClientBorrowingPool::available)
                .description("BetBoom WS slots currently free to borrow")
                .register(meterRegistry);

        Gauge.builder("betboom.ws.pool.backlog", pool, WsClientBorrowingPool::totalInboxBacklog)
                .description("Sum of undrained frames across all BetBoom WS connections — " +
                        "a sustained climb indicates a connection nobody is draining")
                .register(meterRegistry);
    }
}
