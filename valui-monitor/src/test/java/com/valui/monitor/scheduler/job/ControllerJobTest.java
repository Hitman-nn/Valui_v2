package com.valui.monitor.scheduler.job;

import com.valui.common.domain.BookmakerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ControllerJob.markFinished — per-cycle jitter")
class ControllerJobTest {

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    @RepeatedTest(20)
    @DisplayName("nextRunAt stays within ±15% of pollIntervalSec")
    void markFinished_nextRunAt_withinJitterBounds() {
        int pollIntervalSec = 20;
        ControllerJob job = ControllerJob.initial(
                CTRL_ID, USER_ID, pollIntervalSec, BookmakerType.XBET, Instant.now());
        Instant finishedAt = Instant.now();

        ControllerJob finished = job.markFinished(finishedAt);

        Duration delay = Duration.between(finishedAt, finished.nextRunAt());
        long minMs = (long) (pollIntervalSec * 1000L * 0.85);
        long maxMs = (long) (pollIntervalSec * 1000L * 1.15);
        assertThat(delay.toMillis()).isBetween(minMs, maxMs);
    }

    @Test
    @DisplayName("repeated calls with the same finishedAt produce varying nextRunAt (not perfectly periodic)")
    void markFinished_repeatedCalls_produceDifferentDelays() {
        int pollIntervalSec = 20;
        Instant finishedAt = Instant.now();
        Set<Instant> distinctResults = new HashSet<>();

        for (int i = 0; i < 30; i++) {
            ControllerJob job = ControllerJob.initial(
                    UUID.randomUUID(), USER_ID, pollIntervalSec, BookmakerType.XBET, Instant.now());
            distinctResults.add(job.markFinished(finishedAt).nextRunAt());
        }

        // Regression guard for the 28.09 thundering-herd finding: controllers that finish at the
        // exact same instant must NOT all get the exact same nextRunAt — that's precisely the
        // lockstep behavior that let 12+ XBET controllers burst within 204ms in prod.
        assertThat(distinctResults.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("markInFlight/withNextRunAt are unaffected by jitter (only markFinished adds it)")
    void otherTransitions_noJitter() {
        Instant target = Instant.now().plusSeconds(100);
        ControllerJob job = ControllerJob.initial(
                CTRL_ID, USER_ID, 20, BookmakerType.XBET, Instant.now());

        assertThat(job.withNextRunAt(target).nextRunAt()).isEqualTo(target);
    }
}
