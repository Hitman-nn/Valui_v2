package com.valui.monitor.scheduler;

import com.valui.monitor.history.PollHistoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests that ControllerTask correctly enforces the global and per-user concurrency limits.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConcurrencyLimit — semaphore and per-user guard tests")
class ConcurrencyLimitTest {

    @Mock ControllerTaskExecutor taskExecutor;

    MonitorMetrics metrics;
    Semaphore globalSemaphore;
    ConcurrentHashMap<UUID, AtomicInteger> perUserCounter;

    static final int MAX_GLOBAL   = 2;
    static final int MAX_PER_USER = 1;

    @BeforeEach
    void setUp() {
        metrics        = new MonitorMetrics(new SimpleMeterRegistry());
        globalSemaphore = new Semaphore(MAX_GLOBAL);
        perUserCounter  = new ConcurrentHashMap<>();
    }

    // ── Global semaphore ──────────────────────────────────────────────────────

    @Test
    @DisplayName("N+1 task skipped when global semaphore exhausted")
    void globalLimit_nPlusOne_isSkipped() throws InterruptedException {
        // Pre-exhaust the semaphore directly — no need for running VTs
        globalSemaphore.acquire(MAX_GLOBAL);
        assertThat(globalSemaphore.availablePermits()).isZero();

        // Next task must be skipped immediately
        AtomicInteger loadContextCalls = new AtomicInteger(0);
        given(taskExecutor.loadContext(any())).willAnswer(inv -> {
            loadContextCalls.incrementAndGet();
            return java.util.Optional.empty();
        });

        ControllerTask overflow = makeTask(UUID.randomUUID(), UUID.randomUUID());
        overflow.run();

        // loadContext was never called because the semaphore prevented execution
        assertThat(loadContextCalls.get()).isZero();

        // Release and verify semaphore restored (task released 0 extra permits)
        globalSemaphore.release(MAX_GLOBAL);
        assertThat(globalSemaphore.availablePermits()).isEqualTo(MAX_GLOBAL);
    }

    // ── Per-user limit ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Per-user limit: second task for same user is skipped while first is running")
    void perUserLimit_secondTask_isSkipped() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        UUID ctrl1  = UUID.randomUUID();
        UUID ctrl2  = UUID.randomUUID();

        CountDownLatch task1Running = new CountDownLatch(1);
        CountDownLatch release1     = new CountDownLatch(1);
        AtomicInteger task2LoadCalls = new AtomicInteger(0);

        // Task 1: slow task — blocks until released
        given(taskExecutor.loadContext(ctrl1)).willAnswer(inv -> {
            task1Running.countDown();
            release1.await(5, TimeUnit.SECONDS); // timeout prevents hanging
            return java.util.Optional.empty();
        });
        // Task 2: fast — tracks if loadContext was called
        given(taskExecutor.loadContext(ctrl2)).willAnswer(inv -> {
            task2LoadCalls.incrementAndGet();
            return java.util.Optional.empty();
        });

        // Start task 1 on a VT
        ControllerTask task1 = makeTask(ctrl1, userId);
        Thread t1 = Thread.ofVirtual().start(task1::run);

        // Wait for task 1 to be running
        boolean task1Started = task1Running.await(3, TimeUnit.SECONDS);
        assertThat(task1Started).as("Task 1 should start").isTrue();

        // Task 2 should be skipped (per-user slot is full)
        ControllerTask task2 = makeTask(ctrl2, userId);
        task2.run();
        assertThat(task2LoadCalls.get()).isZero();

        // Release task 1, then task 2 can run
        release1.countDown();
        t1.join(3000);

        // Now task 2 runs successfully
        task2.run();
        assertThat(task2LoadCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Different users run concurrently without blocking each other")
    void differentUsers_runConcurrently() throws InterruptedException {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release     = new CountDownLatch(1);

        given(taskExecutor.loadContext(any())).willAnswer(inv -> {
            bothRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
            return java.util.Optional.empty();
        });

        Thread th1 = Thread.ofVirtual().start(makeTask(UUID.randomUUID(), user1)::run);
        Thread th2 = Thread.ofVirtual().start(makeTask(UUID.randomUUID(), user2)::run);

        // Both users run concurrently — neither blocks the other
        boolean started = bothRunning.await(3, TimeUnit.SECONDS);
        assertThat(started).as("Both tasks should run concurrently").isTrue();

        release.countDown();
        th1.join(3000);
        th2.join(3000);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ControllerTask makeTask(UUID controllerId, UUID userId) {
        return new ControllerTask(
                controllerId, userId, taskExecutor,
                globalSemaphore, perUserCounter,
                MAX_PER_USER, metrics, mock(PollHistoryService.class));
    }
}
