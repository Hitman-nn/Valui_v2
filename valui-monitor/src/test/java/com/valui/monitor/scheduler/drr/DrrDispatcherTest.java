package com.valui.monitor.scheduler.drr;

import com.valui.common.domain.BookmakerType;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.ControllerTaskExecutor;
import com.valui.monitor.scheduler.MonitorMetrics;
import com.valui.monitor.scheduler.job.ControllerJob;
import com.valui.monitor.scheduler.job.JobRegistry;
import com.valui.monitor.scheduler.state.SchedulerStateStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * REGRESSION: the whole point of splitting one shared {@code Semaphore(maxConcurrentTasks)} into
 * one per {@link BookmakerType} (24.09 findings — Fonbet convoy 27.08 and BetBoom reconnect storm
 * 24.09 both starved every OTHER bookmaker's dispatch slots, not just their own) — this test
 * proves that isolation actually holds: a fully saturated bookmaker cannot block a different
 * bookmaker's task from dispatching.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DrrDispatcher — per-bookmaker slot isolation")
class DrrDispatcherTest {

    @Mock ControllerTaskExecutor taskExecutor;
    @Mock PollHistoryService     pollHistory;
    @Mock SchedulerStateStore    stateStore;

    JobRegistry     jobRegistry;
    MonitorMetrics  metrics;
    MonitorProperties props;
    DrrDispatcher   dispatcher;

    @BeforeEach
    void setUp() {
        jobRegistry = new JobRegistry(stateStore);
        metrics = new MonitorMetrics(new SimpleMeterRegistry());
        props = new MonitorProperties();
        // One slot per bookmaker exactly — makes it trivial to fully saturate a single
        // bookmaker's pool with just one stuck task.
        props.setMaxConcurrentTasks(BookmakerType.values().length);
        dispatcher = new DrrDispatcher(jobRegistry, taskExecutor, metrics, pollHistory, props);
        dispatcher.start();
    }

    @AfterEach
    void tearDown() {
        dispatcher.stop();
    }

    @Test
    @DisplayName("A fully saturated bookmaker's pool does not block a different bookmaker's task")
    void oneBookmakerSaturated_doesNotStarveAnother() throws InterruptedException {
        UUID stuckCtrl  = UUID.randomUUID();
        UUID stuckUser  = UUID.randomUUID();
        UUID otherCtrl  = UUID.randomUUID();
        UUID otherUser  = UUID.randomUUID();

        CountDownLatch stuckStarted    = new CountDownLatch(1);
        CountDownLatch release         = new CountDownLatch(1);
        CountDownLatch otherDispatched = new CountDownLatch(1);

        given(taskExecutor.loadContext(stuckCtrl)).willAnswer(inv -> {
            stuckStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });
        given(taskExecutor.loadContext(otherCtrl)).willAnswer(inv -> {
            otherDispatched.countDown();
            return Optional.empty();
        });

        // Occupy FONBET's one and only slot indefinitely (holds it until `release` fires).
        ControllerJob stuckJob = ControllerJob.initial(
                stuckCtrl, stuckUser, 20, BookmakerType.FONBET, Instant.now());
        jobRegistry.put(stuckJob);
        dispatcher.enqueue(stuckJob);
        assertThat(stuckStarted.await(3, TimeUnit.SECONDS))
                .as("Setup: FONBET's task should have started and occupied its only slot")
                .isTrue();

        // A completely different bookmaker (XBET) must still dispatch normally — it has its own
        // reserved slot, untouched by FONBET's being fully occupied.
        ControllerJob otherJob = ControllerJob.initial(
                otherCtrl, otherUser, 20, BookmakerType.XBET, Instant.now());
        jobRegistry.put(otherJob);
        dispatcher.enqueue(otherJob);

        assertThat(otherDispatched.await(3, TimeUnit.SECONDS))
                .as("XBET's task should dispatch even though FONBET's single slot is fully occupied")
                .isTrue();

        release.countDown();
    }
}
