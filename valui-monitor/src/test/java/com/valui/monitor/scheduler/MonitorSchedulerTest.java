package com.valui.monitor.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ControllerScheduleInfo;
import com.valui.monitor.scheduler.drr.DrrDispatcher;
import com.valui.monitor.scheduler.job.ControllerJob;
import com.valui.monitor.scheduler.job.JobRegistry;
import com.valui.monitor.scheduler.state.SchedulerStateStore;
import com.valui.user.api.ControllerPortService;
import com.valui.user.event.UserBanEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MonitorScheduler — unit tests")
class MonitorSchedulerTest {

    @Mock ControllerTaskExecutor    taskExecutor;
    @Mock MonitorMetrics            metrics;
    @Mock EventDeduplicationService dedup;
    @Mock ControllerPortService     controllerPort;
    @Mock DrrDispatcher             dispatcher;
    @Mock SchedulerStateStore       stateStore;
    @Mock com.valui.monitor.scheduler.SchedulerConfigStore configStore;

    MonitorProperties props;
    JobRegistry       jobRegistry;
    MonitorScheduler  scheduler;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final long TG_ID   = 42L;

    @BeforeEach
    void setUp() {
        props       = new MonitorProperties();
        props.setDefaultPollIntervalSec(10);
        jobRegistry = new JobRegistry(stateStore);

        given(taskExecutor.loadAllActiveForScheduling()).willReturn(List.of());
        given(stateStore.loadNextRunAt(any())).willReturn(Optional.empty());
        // don't throw on controllerPort.hasActiveSubscriptions — returns false by default

        scheduler = new MonitorScheduler(
                taskExecutor, props, metrics, dedup, controllerPort,
                jobRegistry, dispatcher, stateStore, configStore);
        scheduler.init();
    }

    // ── scheduleController ────────────────────────────────────────────────────

    @Test
    @DisplayName("scheduleController: adds controller to registry")
    void scheduleController_addsToRegistry() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);

        assertThat(scheduler.getScheduledControllerIds()).contains(CTRL_ID);
        verify(metrics).onControllerScheduled();
        verify(dispatcher).enqueue(any(ControllerJob.class));
    }

    @Test
    @DisplayName("scheduleController: duplicate call is idempotent")
    void scheduleController_duplicate_ignored() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.scheduleController(CTRL_ID, USER_ID, 30); // second call

        assertThat(scheduler.getScheduledControllerIds()).hasSize(1);
        verify(metrics, times(1)).onControllerScheduled();
        verify(dispatcher, times(1)).enqueue(any(ControllerJob.class));
    }

    // ── unscheduleController ──────────────────────────────────────────────────

    @Test
    @DisplayName("unscheduleController: removes from registry and cancels in dispatcher")
    void unscheduleController_removesAndCancels() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);
        scheduler.unscheduleController(CTRL_ID);

        assertThat(scheduler.getScheduledControllerIds()).doesNotContain(CTRL_ID);
        verify(metrics).onControllerUnscheduled();
        verify(dispatcher).cancel(CTRL_ID);
    }

    @Test
    @DisplayName("unscheduleController: unknown id is a no-op")
    void unscheduleController_unknown_noop() {
        scheduler.unscheduleController(UUID.randomUUID());
        verify(metrics, never()).onControllerUnscheduled();
        verify(dispatcher, never()).cancel(any());
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

    // ── UserBanEvent (UNBAN) ──────────────────────────────────────────────────

    @Test
    @DisplayName("UserBanEvent UNBAN → clears dedup and resets lastCheckedAt in one batch")
    void on_userUnban_clearsDedupAndResetsBatch() {
        UUID ctrl1 = UUID.randomUUID(), ctrl2 = UUID.randomUUID();
        given(taskExecutor.loadActiveForUser(USER_ID)).willReturn(List.of(
                new ControllerScheduleInfo(ctrl1, USER_ID, TG_ID, 60, BookmakerType.XBET),
                new ControllerScheduleInfo(ctrl2, USER_ID, TG_ID, 60, BookmakerType.FONBET)));

        scheduler.on(new UserBanEvent(USER_ID, "UNBAN", null));

        verify(dedup).clearController(ctrl1);
        verify(dedup).clearController(ctrl2);
        verify(controllerPort).resetLastCheckedAtBatch(List.of(ctrl1, ctrl2));
    }

    @Test
    @DisplayName("UserBanEvent BAN → no dedup or DB reset")
    void on_userBan_noop() {
        scheduler.on(new UserBanEvent(USER_ID, "BAN", null));

        verify(dedup, never()).clearController(any());
        verify(controllerPort, never()).resetLastCheckedAtBatch(any());
    }

    @Test
    @DisplayName("UserBanEvent UNBAN with no controllers → no-op")
    void on_userUnban_noControllers_noop() {
        given(taskExecutor.loadActiveForUser(USER_ID)).willReturn(List.of());

        scheduler.on(new UserBanEvent(USER_ID, "UNBAN", null));

        verify(dedup, never()).clearController(any());
        verify(controllerPort, never()).resetLastCheckedAtBatch(any());
    }

    // ── rescheduleAll ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rescheduleAll: clears existing and reloads from DB")
    void rescheduleAll_reloadsFromDB() {
        scheduler.scheduleController(CTRL_ID, USER_ID, 30);

        UUID freshCtrl = UUID.randomUUID();
        given(taskExecutor.loadAllActiveForScheduling()).willReturn(List.of(
                new ControllerScheduleInfo(freshCtrl, USER_ID, TG_ID, 60, BookmakerType.FONBET)));

        scheduler.rescheduleAll();

        assertThat(scheduler.getScheduledControllerIds()).containsExactly(freshCtrl);
        assertThat(scheduler.getScheduledControllerIds()).doesNotContain(CTRL_ID);
    }
}
