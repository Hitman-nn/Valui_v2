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
 *
 * <p><b>2026-08 recalibration</b>: the first production run of this logger (before
 * {@link WsClientBorrowingPool#drainIdleSlots()} existed) showed the original 500-frame WARN
 * threshold firing continuously for hours — {@code backlog} climbed to the per-connection cap
 * (6 × 2000 = 12000) within ~4h and stayed pinned there, with {@code dropped} in the hundreds
 * per 10-min window throughout. Root cause: BetBoom keeps pushing odds updates for every
 * subscription a connection has ever made regardless of whether anyone's borrowing it, and
 * nothing was proactively draining that traffic while a slot sat idle in the free pool — it
 * only got cleared reactively, at the next borrow or hygiene recycle. Since idle slots are now
 * drained every {@link WsPoolProperties#getIdleDrainInterval()}, a sustained nonzero backlog or
 * any {@code dropped>0} again means something actionable (genuine contention exceeding
 * per-connection capacity), not "the pool has been idle for a few minutes" — hence WARN stays
 * appropriate at a much lower bar than before.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsPoolHealthLogger {

    // With idle slots now proactively drained (see class javadoc), backlog should sit near-zero
    // in steady state — a connection only accumulates frames while actively serving a request,
    // which typically completes in well under a second.
    private static final long BACKLOG_WARN_THRESHOLD = 100;

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
        long idleDrained = pool.drainIdleFramesDrained();

        boolean allQuiet = backlog < 20 && dropped == 0 && failureRc == 0;
        boolean concerning = backlog >= BACKLOG_WARN_THRESHOLD || dropped > 0;

        if (allQuiet) {
            log.debug("[BB-WS 10m] connected={}/{} backlog={} dropped={} idleDrained={} reconnects(fail={},hygiene={})",
                    connected, size, backlog, dropped, idleDrained, failureRc, hygieneRc);
        } else if (concerning) {
            log.warn("[BB-WS 10m] connected={}/{} available={} backlog={} dropped={} idleDrained={} reconnects(fail={},hygiene={})",
                    connected, size, available, backlog, dropped, idleDrained, failureRc, hygieneRc);
        } else {
            log.info("[BB-WS 10m] connected={}/{} backlog={} dropped={} idleDrained={} reconnects(fail={},hygiene={})",
                    connected, size, backlog, dropped, idleDrained, failureRc, hygieneRc);
        }
    }
}
