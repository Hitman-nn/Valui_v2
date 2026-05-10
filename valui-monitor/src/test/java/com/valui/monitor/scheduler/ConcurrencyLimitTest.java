package com.valui.monitor.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.ControllerTaskExecutor.TaskContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ControllerTask}: fetch budget enforcement and parallel execution.
 * Concurrency guards (semaphore, per-user limit) were moved to DrrDispatcher and are no longer here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ControllerTask — fetch budget and isolation tests")
class ConcurrencyLimitTest {

    @Mock ControllerTaskExecutor taskExecutor;

    MonitorMetrics metrics;

    static final int FETCH_BUDGET_MS = 500;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    static final TaskContext DUMMY_CTX = new TaskContext(
            CTRL_ID, USER_ID, 42L, BookmakerType.FONBET,
            "https://fonbet.ru/sport/football/123",
            "123", null, ControllerType.TOURNAMENT);

    @BeforeEach
    void setUp() {
        metrics = new MonitorMetrics(new SimpleMeterRegistry());
    }

    // ── Normal execution ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Task calls persistNewEvents when fetch succeeds within budget")
    void fetchWithinBudget_callsPersist() {
        given(taskExecutor.loadContext(CTRL_ID)).willReturn(Optional.of(DUMMY_CTX));
        given(taskExecutor.isParserAvailable(any())).willReturn(true);
        given(taskExecutor.fetch(any())).willReturn(List.of());
        given(taskExecutor.persistNewEvents(any(), any())).willReturn(0);

        makeTask(CTRL_ID, USER_ID).run();

        verify(taskExecutor).persistNewEvents(any(), any());
    }

    // ── Fetch budget ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Task records 'timeout' history entry when fetch exceeds budget")
    void fetchExceedsBudget_recordsTimeout() throws InterruptedException {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch unblock      = new CountDownLatch(1);

        given(taskExecutor.loadContext(CTRL_ID)).willReturn(Optional.of(DUMMY_CTX));
        given(taskExecutor.isParserAvailable(any())).willReturn(true);
        given(taskExecutor.fetch(any())).willAnswer(inv -> {
            fetchStarted.countDown();
            unblock.await(10, TimeUnit.SECONDS);
            return List.of();
        });

        PollHistoryService pollHistory   = mock(PollHistoryService.class);
        AtomicInteger timeoutRecorded    = new AtomicInteger(0);
        doAnswer(inv -> {
            if ("timeout".equals(inv.<String>getArgument(4))) timeoutRecorded.incrementAndGet();
            return null;
        }).when(pollHistory).record(any(), any(), anyLong(), anyInt(), any());

        ControllerTask task = new ControllerTask(
                CTRL_ID, USER_ID, taskExecutor, metrics, pollHistory, FETCH_BUDGET_MS);

        Thread vt = Thread.ofVirtual().start(task::run);

        assertThat(fetchStarted.await(2, TimeUnit.SECONDS)).isTrue();
        vt.join(FETCH_BUDGET_MS * 3L);
        unblock.countDown(); // cleanup

        assertThat(timeoutRecorded.get()).isEqualTo(1);
        verify(taskExecutor, never()).persistNewEvents(any(), any());
    }

    // ── Parallel execution ────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple tasks for different users run concurrently on virtual threads")
    void multipleTasks_runConcurrently() throws InterruptedException {
        UUID ctrl2 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        TaskContext ctx2 = new TaskContext(
                ctrl2, user2, 43L, BookmakerType.OLIMP,
                "https://olimpbet.ru/sport/football/456",
                "456", null, ControllerType.TOURNAMENT);

        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release     = new CountDownLatch(1);

        given(taskExecutor.loadContext(CTRL_ID)).willAnswer(inv -> {
            bothRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });
        given(taskExecutor.loadContext(ctrl2)).willAnswer(inv -> {
            bothRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        Thread th1 = Thread.ofVirtual().start(makeTask(CTRL_ID, USER_ID)::run);
        Thread th2 = Thread.ofVirtual().start(makeTask(ctrl2, user2)::run);

        assertThat(bothRunning.await(3, TimeUnit.SECONDS))
                .as("Both tasks should run concurrently on virtual threads")
                .isTrue();

        release.countDown();
        th1.join(3000);
        th2.join(3000);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ControllerTask makeTask(UUID controllerId, UUID userId) {
        return new ControllerTask(
                controllerId, userId, taskExecutor,
                metrics, mock(PollHistoryService.class), FETCH_BUDGET_MS);
    }
}
