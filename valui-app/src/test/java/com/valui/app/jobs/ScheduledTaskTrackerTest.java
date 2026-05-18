package com.valui.app.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScheduledTaskTracker — counter accumulation and state transitions")
class ScheduledTaskTrackerTest {

    private ScheduledTaskTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ScheduledTaskTracker();
    }

    @Test
    @DisplayName("first successful run: runCount=1, errorCount=0, status=OK, errorMessage=null")
    void firstSuccessfulRun() {
        tracker.record("MyJob.run", 100, true, null);

        var exec = tracker.getAll().get("MyJob.run");
        assertThat(exec).isNotNull();
        assertThat(exec.runCount()).isEqualTo(1);
        assertThat(exec.errorCount()).isEqualTo(0);
        assertThat(exec.status()).isEqualTo("OK");
        assertThat(exec.lastErrorMessage()).isNull();
        assertThat(exec.durationMs()).isEqualTo(100);
        assertThat(exec.lastRunAt()).isNotNull();
    }

    @Test
    @DisplayName("first failed run: runCount=1, errorCount=1, status=ERROR, errorMessage stored")
    void firstFailedRun() {
        tracker.record("MyJob.run", 50, false, "connection refused");

        var exec = tracker.getAll().get("MyJob.run");
        assertThat(exec.runCount()).isEqualTo(1);
        assertThat(exec.errorCount()).isEqualTo(1);
        assertThat(exec.status()).isEqualTo("ERROR");
        assertThat(exec.lastErrorMessage()).isEqualTo("connection refused");
    }

    @Test
    @DisplayName("runCount accumulates across multiple calls")
    void runCountAccumulates() {
        tracker.record("MyJob.run", 10, true, null);
        tracker.record("MyJob.run", 20, true, null);
        tracker.record("MyJob.run", 30, true, null);

        var exec = tracker.getAll().get("MyJob.run");
        assertThat(exec.runCount()).isEqualTo(3);
        assertThat(exec.errorCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("errorCount increments only on failures")
    void errorCountOnlyOnFailure() {
        tracker.record("MyJob.run", 10, true,  null);
        tracker.record("MyJob.run", 10, false, "err1");
        tracker.record("MyJob.run", 10, true,  null);
        tracker.record("MyJob.run", 10, false, "err2");

        var exec = tracker.getAll().get("MyJob.run");
        assertThat(exec.runCount()).isEqualTo(4);
        assertThat(exec.errorCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("lastErrorMessage updates to the most recent error")
    void lastErrorMessageUpdatesToMostRecent() {
        tracker.record("MyJob.run", 10, false, "first error");
        tracker.record("MyJob.run", 10, false, "second error");

        assertThat(tracker.getAll().get("MyJob.run").lastErrorMessage()).isEqualTo("second error");
    }

    @Test
    @DisplayName("lastErrorMessage cleared to null after a successful run")
    void errorMessageClearedOnSuccess() {
        tracker.record("MyJob.run", 10, false, "some error");
        tracker.record("MyJob.run", 10, true,  null);

        var exec = tracker.getAll().get("MyJob.run");
        assertThat(exec.status()).isEqualTo("OK");
        assertThat(exec.lastErrorMessage()).isNull();
        assertThat(exec.runCount()).isEqualTo(2);
        assertThat(exec.errorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("lastRunAt and durationMs always reflect the most recent run")
    void lastRunAtAndDurationFromLatestRun() {
        tracker.record("MyJob.run", 100, true, null);
        tracker.record("MyJob.run", 999, true, null);

        assertThat(tracker.getAll().get("MyJob.run").durationMs()).isEqualTo(999);
    }

    @Test
    @DisplayName("different keys are tracked independently")
    void independentKeysDoNotInterfere() {
        tracker.record("JobA.run", 10, true,  null);
        tracker.record("JobB.run", 20, false, "boom");
        tracker.record("JobA.run", 10, true,  null);

        assertThat(tracker.getAll().get("JobA.run").runCount()).isEqualTo(2);
        assertThat(tracker.getAll().get("JobA.run").errorCount()).isEqualTo(0);
        assertThat(tracker.getAll().get("JobB.run").runCount()).isEqualTo(1);
        assertThat(tracker.getAll().get("JobB.run").errorCount()).isEqualTo(1);
    }
}
