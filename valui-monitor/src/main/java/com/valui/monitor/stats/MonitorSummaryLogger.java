package com.valui.monitor.stats;

import com.valui.monitor.scheduler.MonitorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Logs a one-liner summary of monitor activity every 10 minutes.
 * Mirrors the pattern of NotificationSummaryLogger in valui-notify.
 * Counters are drained after each log so numbers reflect the last window only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorSummaryLogger {

    private final MonitorMetrics metrics;

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 10)
    public void logSummary() {
        long pollsOk    = metrics.drainPollsOk();
        long pollsError = metrics.drainPollsError();
        long events     = metrics.drainWindowEvents();
        int  queue      = metrics.currentQueueDepth();
        long scheduled  = metrics.currentScheduled();

        boolean hasErrors = pollsError > 0;
        boolean allQuiet  = pollsOk == 0 && pollsError == 0 && events == 0;

        String msg = "[MONITOR 10m] опросов={} ошибок={} событий={} queue={} scheduled={}";
        if (allQuiet) {
            log.debug(msg, pollsOk, pollsError, events, queue, scheduled);
        } else if (hasErrors) {
            log.warn(msg, pollsOk, pollsError, events, queue, scheduled);
        } else {
            log.info(msg, pollsOk, pollsError, events, queue, scheduled);
        }
    }
}
