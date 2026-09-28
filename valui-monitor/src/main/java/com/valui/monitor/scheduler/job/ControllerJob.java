package com.valui.monitor.scheduler.job;

import com.valui.common.domain.BookmakerType;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable snapshot of a scheduled controller's runtime state.
 * Held in {@link JobRegistry} and mirrored to Redis via {@link com.valui.monitor.scheduler.state.SchedulerStateStore}.
 *
 * <p>{@code bookmaker} is static metadata (never changes for a given controller) carried
 * alongside {@code userId}/{@code pollIntervalSec} — {@link com.valui.monitor.scheduler.drr.DrrDispatcher}
 * reads it to route each job to its bookmaker's own reserved concurrency slot instead of one
 * shared global pool (see the dispatcher's class javadoc for why: a single bookmaker's outage
 * used to be able to starve every other bookmaker of dispatch slots).
 */
public record ControllerJob(
        UUID    controllerId,
        UUID    userId,
        int     pollIntervalSec,
        BookmakerType bookmaker,
        Instant nextRunAt,
        Instant lastStartedAt,   // null before first run
        Instant lastFinishedAt,  // null before first run
        boolean inFlight,
        long    version          // bumped on every state transition for CAS safety
) {

    public static ControllerJob initial(UUID controllerId, UUID userId, int pollIntervalSec,
                                         BookmakerType bookmaker, Instant nextRunAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec, bookmaker,
                nextRunAt, null, null, false, 0L);
    }

    public ControllerJob withNextRunAt(Instant nextRunAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec, bookmaker,
                nextRunAt, lastStartedAt, lastFinishedAt, inFlight, version);
    }

    /** Transition to in-flight state. */
    public ControllerJob markInFlight(Instant startedAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec, bookmaker,
                nextRunAt, startedAt, lastFinishedAt, true, version + 1);
    }

    // ±15% of pollIntervalSec — see markFinished() javadoc for why this exists.
    private static final int JITTER_PERCENT = 15;

    /**
     * Transition back to idle; nextRunAt = finishedAt + pollIntervalSec ± jitter.
     *
     * <p>28.09 finding: controllers that happen to land close together in time (e.g. several
     * created around the same moment, or simply converging because their fetches take similar
     * durations) used to stay locked together forever — {@code nextRunAt = finishedAt +
     * pollIntervalSec} just carries the same relative spacing forward every cycle, with no force
     * ever pulling them apart. The only desync in the whole scheduler was a ONE-TIME jitter
     * applied at crash-recovery startup ({@link com.valui.monitor.scheduler.MonitorScheduler}) —
     * every subsequent cycle was perfectly periodic. Confirmed in prod logs: 12+ distinct XBET
     * controllers firing within a 204ms window, every ~20s, indefinitely — a single bookmaker
     * seeing a burst of a dozen simultaneous connections from one IP on a strict clock is a far
     * stronger bot signature than the same aggregate request volume spread across the interval,
     * and directly correlates with recurring IP-level blocks from that bookmaker. Applying a
     * small jitter on every reschedule (not just at startup) keeps controllers spread out
     * instead of drifting back into lockstep — same total request volume, no thundering herd.
     */
    public ControllerJob markFinished(Instant finishedAt) {
        long baseMs = pollIntervalSec * 1000L;
        long jitterRangeMs = Math.max(1, baseMs * JITTER_PERCENT / 100);
        long jitterMs = ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1);
        return new ControllerJob(controllerId, userId, pollIntervalSec, bookmaker,
                finishedAt.plusMillis(baseMs + jitterMs), lastStartedAt, finishedAt, false, version + 1);
    }
}
