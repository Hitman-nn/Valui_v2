package com.valui.monitor.scheduler;

import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.event.SubscriptionChangedEvent;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ControllerScheduleInfo;
import com.valui.common.domain.BookmakerType;
import com.valui.user.api.ControllerPortService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MonitorScheduler — unit tests")
class MonitorSchedulerTest {

    @Mock ControllerTaskExecutor taskExecutor;
    @Mock MonitorMetrics metrics;
    @Mock com.valui.monitor.dedup.EventDeduplicationService dedup;
    @Mock ControllerPortService controllerPort;

    MonitorProperties props;
    MonitorScheduler scheduler;

    /** Stub trigger pool: scheduleWithFixedDelay returns a cancellable future but never actually fires. */
    private ScheduledExecutorService stubTriggerPool;
    private ExecutorService stubTaskPool;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final long TG_ID   = 42L;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        props = new MonitorProperties();
        props.setMaxConcurrentTasks(5);
        props.setMaxTasksPerUser(2);
        props.setDefaultPollIntervalSec(10);

        stubTriggerPool = mock(ScheduledExecutorService.class);
        stubTaskPool    = mock(ExecutorService.class);
        // scheduleWithFixedDelay must return a non-null ScheduledFuture
        given(stubTriggerPool.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .willAnswer(inv -> mock(ScheduledFuture.class));

        given(taskExecutor.loadAllActiveForScheduling()).willReturn(List.of());
        scheduler = new MonitorScheduler(taskExecutor, props, metrics, dedup, controllerPort, stubTriggerPool, stubTaskPool);
        scheduler.init();
    }

    // ── scheduleController ────────────────────────────────────────────────────

    @Test
    @DisplayName("scheduleController: adds controller id to scheduled set")
    void scheduleController_addsToScheduled() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);

        assertThat(scheduler.getScheduledControllerIds()).contains(CTRL_ID);
        verify(metrics).onControllerScheduled();
    }

    @Test
    @DisplayName("scheduleController: duplicate call is ignored")
    void scheduleController_duplicate_ignored() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.scheduleController(CTRL_ID, USER_ID, 30); // second call

        assertThat(scheduler.getScheduledControllerIds()).hasSize(1);
        verify(metrics, times(1)).onControllerScheduled(); // only once
    }

    // ── unscheduleController ──────────────────────────────────────────────────

    @Test
    @DisplayName("unscheduleController: removes from scheduled set")
    void unscheduleController_removesFromScheduled() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.unscheduleController(CTRL_ID);

        assertThat(scheduler.getScheduledControllerIds()).doesNotContain(CTRL_ID);
        verify(metrics).onControllerUnscheduled();
    }

    @Test
    @DisplayName("unscheduleController: unknown id is a no-op")
    void unscheduleController_unknown_noop() {
        scheduler.unscheduleController(UUID.randomUUID());
        verify(metrics, never()).onControllerUnscheduled();
    }

    // ── Event listeners ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ControllerAddedEvent listener schedules the controller")
    void on_controllerAdded_schedules() {
        scheduler.on(new ControllerAddedEvent(CTRL_ID, USER_ID, TG_ID, BookmakerType.XBET, 60));

        assertThat(scheduler.getScheduledControllerIds()).contains(CTRL_ID);
    }

    @Test
    @DisplayName("ControllerRemovedEvent listener unschedules the controller")
    void on_controllerRemoved_unschedules() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.on(new ControllerRemovedEvent(CTRL_ID, USER_ID));

        assertThat(scheduler.getScheduledControllerIds()).doesNotContain(CTRL_ID);
    }

    @Test
    @DisplayName("SubscriptionChangedEvent listener reschedules user's controllers")
    void on_subscriptionChanged_reschedulesUser() {
        // seed two controllers for USER_ID
        UUID ctrl2 = UUID.randomUUID();
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.scheduleController(ctrl2, USER_ID, 30);

        // after plan change the poll interval halves
        given(taskExecutor.loadActiveForUser(USER_ID)).willReturn(List.of(
                new ControllerScheduleInfo(CTRL_ID, USER_ID, TG_ID, 15),
                new ControllerScheduleInfo(ctrl2, USER_ID, TG_ID, 15)));

        scheduler.on(new SubscriptionChangedEvent(USER_ID, TG_ID, "PRO", 15));

        // Both controllers must still be in the scheduled set
        assertThat(scheduler.getScheduledControllerIds()).contains(CTRL_ID, ctrl2);
    }

    // ── rescheduleAll ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rescheduleAll: clears existing and reloads from DB")
    void rescheduleAll_reloadsFromDB() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);

        UUID freshCtrl = UUID.randomUUID();
        given(taskExecutor.loadAllActiveForScheduling()).willReturn(List.of(
                new ControllerScheduleInfo(freshCtrl, USER_ID, TG_ID, 60)));

        scheduler.rescheduleAll();

        assertThat(scheduler.getScheduledControllerIds()).containsExactly(freshCtrl);
        assertThat(scheduler.getScheduledControllerIds()).doesNotContain(CTRL_ID);
    }
}
