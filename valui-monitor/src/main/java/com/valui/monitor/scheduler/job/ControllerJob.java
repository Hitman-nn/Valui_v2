package com.valui.monitor.scheduler.job;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a scheduled controller's runtime state.
 * Held in {@link JobRegistry} and mirrored to Redis via {@link com.valui.monitor.scheduler.state.SchedulerStateStore}.
 */
public record ControllerJob(
        UUID    controllerId,
        UUID    userId,
        int     pollIntervalSec,
        Instant nextRunAt,
        Instant lastStartedAt,   // null before first run
        Instant lastFinishedAt,  // null before first run
        boolean inFlight,
        long    version          // bumped on every state transition for CAS safety
) {

    public static ControllerJob initial(UUID controllerId, UUID userId, int pollIntervalSec, Instant nextRunAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec,
                nextRunAt, null, null, false, 0L);
    }

    public ControllerJob withNextRunAt(Instant nextRunAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec,
                nextRunAt, lastStartedAt, lastFinishedAt, inFlight, version);
    }

    /** Transition to in-flight state. */
    public ControllerJob markInFlight(Instant startedAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec,
                nextRunAt, startedAt, lastFinishedAt, true, version + 1);
    }

    /** Transition back to idle; nextRunAt = finishedAt + pollIntervalSec. */
    public ControllerJob markFinished(Instant finishedAt) {
        return new ControllerJob(controllerId, userId, pollIntervalSec,
                finishedAt.plusSeconds(pollIntervalSec), lastStartedAt, finishedAt, false, version + 1);
    }
}
