package com.valui.parser.bookmaker.betboom.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Logs a one-liner summary of BetBoom WS pool health every 10 minutes — mirrors
 * MonitorSummaryLogger / NotificationSummaryLogger so operators have one consistent place to
 * look for periodic health signals instead of per-event log spam.
 *
 * Also the early-warning system for the class of leak that caused the 2026-08 OOM: a growing
 * {@code backlog} between windows means some slot's inbox is filling up faster than it's being
 * drained — visible here in minutes, instead of only discoverable via a heap dump after a crash.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsPoolHealthLogger {

    // A single connection carrying a few hundred queued frames between polls is unremarkable
    // (see WsClient.MAX_INBOX = 2000); this is well below the drop threshold and only flags
    // a backlog that's clearly trending toward trouble.
    private static final long BACKLOG_WARN_THRESHOLD = 500;

    private final WsClientBorrowingPool pool;

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 10)
    void logSummary() {
        int connected  = pool.connected();
        int available  = pool.available();
        int size       = pool.size();
        long backlog   = pool.totalInboxBacklog();
        long dropped   = pool.drainFramesDropped();
        long failureRc = pool.drainFailureReconnects();
        long hygieneRc = pool.drainHygieneReconnects();

        boolean allQuiet = backlog < 50 && dropped == 0 && failureRc == 0;
        boolean concerning = backlog >= BACKLOG_WARN_THRESHOLD || dropped > 0;

        if (allQuiet) {
            log.debug("[BB-WS 10m] connected={}/{} backlog={} dropped={} reconnects(fail={},hygiene={})",
                    connected, size, backlog, dropped, failureRc, hygieneRc);
        } else if (concerning) {
            log.warn("[BB-WS 10m] connected={}/{} available={} backlog={} dropped={} reconnects(fail={},hygiene={})",
                    connected, size, available, backlog, dropped, failureRc, hygieneRc);
        } else {
            log.info("[BB-WS 10m] connected={}/{} backlog={} dropped={} reconnects(fail={},hygiene={})",
                    connected, size, backlog, dropped, failureRc, hygieneRc);
        }
    }
}
