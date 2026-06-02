package com.valui.monitor.stats;

import com.valui.monitor.scheduler.MonitorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs a one-liner summary of monitor activity every 10 minutes.
 * Mirrors the pattern of NotificationSummaryLogger in valui-notify.
 * Counters are drained after each log so numbers reflect the last window only.
 *
 * Publishes {@link MonitorStormEvent} when >= STORM_THRESHOLD errors appear in
 * STORM_CONSECUTIVE_WINDOWS consecutive 10-minute windows — signals a sustained
 * network or infrastructure incident rather than an isolated transient failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorSummaryLogger {

    private static final long STORM_THRESHOLD           = 5;
    private static final int  STORM_CONSECUTIVE_WINDOWS = 2;

    private final MonitorMetrics            metrics;
    private final ApplicationEventPublisher eventPublisher;

    private final AtomicInteger consecutiveStormWindows = new AtomicInteger(0);

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 10)
    public void logSummary() {
        long pollsOk    = metrics.drainPollsOk();
        long pollsError = metrics.drainPollsError();
        long cbSkipped  = metrics.drainPollsCbSkipped();
        long events     = metrics.drainWindowEvents();
        int  queue      = metrics.currentQueueDepth();
        long scheduled  = metrics.currentScheduled();

        String cbPart = cbSkipped > 0 ? " cb-skip=" + cbSkipped : "";

        boolean hasErrors = pollsError > 0;
        boolean allQuiet  = pollsOk == 0 && pollsError == 0 && cbSkipped == 0 && events == 0;

        if (allQuiet) {
            log.debug("[MONITOR 10m] опросов={} ошибок={} событий={} queue={} scheduled={}",
                    pollsOk, pollsError, events, queue, scheduled);
        } else if (hasErrors) {
            long total = pollsOk + pollsError;
            String errPct = String.format("%.1f%%", pollsError * 100.0 / total);
            log.warn("[MONITOR 10m] опросов={} ошибок={} ({}){} событий={} queue={} scheduled={}",
                    pollsOk, pollsError, errPct, cbPart, events, queue, scheduled);
        } else {
            log.info("[MONITOR 10m] опросов={} ошибок={}{} событий={} queue={} scheduled={}",
                    pollsOk, pollsError, cbPart, events, queue, scheduled);
        }

        checkStorm(pollsError, pollsOk + pollsError);
    }

    private void checkStorm(long errors, long total) {
        if (errors >= STORM_THRESHOLD) {
            int consecutive = consecutiveStormWindows.incrementAndGet();
            if (consecutive == STORM_CONSECUTIVE_WINDOWS) {
                eventPublisher.publishEvent(new MonitorStormEvent(this, errors, total, consecutive));
                // Reset after firing so the alert does not repeat on every subsequent bad window.
                // If the storm continues beyond this point the counter restarts from 0, meaning
                // another alert fires only after STORM_CONSECUTIVE_WINDOWS more bad windows in a row.
                // A clean window resets the counter as well (see else-branch), so a single recovery
                // interval followed by a new storm will produce a fresh alert as expected.
                consecutiveStormWindows.set(0);
            }
        } else {
            consecutiveStormWindows.set(0);
        }
    }
}
