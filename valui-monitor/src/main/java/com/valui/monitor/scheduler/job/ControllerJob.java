package com.valui.monitor.scheduler.job;

import com.valui.common.domain.BookmakerType;

import java.time.Instant;
import java.util.UUID;

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

    /** Transition back to idle; nextRunAt = finishedAt + pollIntervalSec. */
    public ControllerJob markFinished(Instant finishedAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec, bookmaker,
                finishedAt.plusSeconds(pollIntervalSec), lastStartedAt, finishedAt, false, version + 1);
    }
}
