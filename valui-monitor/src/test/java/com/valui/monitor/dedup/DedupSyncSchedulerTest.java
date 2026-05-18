package com.valui.monitor.dedup;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.monitor.config.MonitorProperties;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DedupSyncScheduler — unit tests")
class DedupSyncSchedulerTest {

    @Mock EventDeduplicationService dedup;
    @Mock ControllerPortService controllerPort;
    @Mock DetectedEventPortService detectedEventPort;
    @Mock MonitorProperties props;

    @InjectMocks DedupSyncScheduler scheduler;

    static final UUID CTRL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(props.getDedupTtlDays()).willReturn(7);
        given(props.getDedupCleanupBatchSize()).willReturn(500);
        // Default: no expired rows — cleanup loop executes once and stops
        given(detectedEventPort.deleteExpiredBatch(any(), anyInt())).willReturn(0);
    }

    @Test
    @DisplayName("sync: for each active controller calls syncSeenEvents with DB event IDs")
    void sync_callsSyncSeenEventsPerController() {
        ControllerEntity ctrl = controllerEntity(CTRL_ID);
        given(controllerPort.findAllActive()).willReturn(List.of(ctrl));
        given(detectedEventPort.findAllExternalIdsByControllerId(eq(CTRL_ID)))
                .willReturn(List.of("e1", "e2", "e3"));
        given(detectedEventPort.findExternalIdsByControllerIdSince(eq(CTRL_ID), any()))
                .willReturn(List.of("e1", "e2", "e3"));

        scheduler.sync();

        verify(dedup).syncSeenEvents(eq(CTRL_ID), eq(Set.of("e1", "e2", "e3")), eq(Set.of("e1", "e2", "e3")));
    }

    @Test
    @DisplayName("sync: no active controllers → no dedup calls")
    void sync_noActiveControllers_noOp() {
        given(controllerPort.findAllActive()).willReturn(List.of());

        scheduler.sync();

        verify(dedup, org.mockito.Mockito.never()).syncSeenEvents(any(), any(), any());
    }

    @Test
    @DisplayName("sync: per-controller error does not abort remaining controllers")
    void sync_singleControllerError_continuesOthers() {
        UUID ctrl1 = UUID.randomUUID();
        UUID ctrl2 = UUID.randomUUID();
        given(controllerPort.findAllActive())
                .willReturn(List.of(controllerEntity(ctrl1), controllerEntity(ctrl2)));
        given(detectedEventPort.findExternalIdsByControllerIdSince(eq(ctrl1), any()))
                .willThrow(new RuntimeException("DB error"));
        given(detectedEventPort.findAllExternalIdsByControllerId(eq(ctrl2)))
                .willReturn(List.of("good-event"));
        given(detectedEventPort.findExternalIdsByControllerIdSince(eq(ctrl2), any()))
                .willReturn(List.of("good-event"));

        scheduler.sync(); // must not throw

        verify(dedup).syncSeenEvents(eq(ctrl2), eq(Set.of("good-event")), eq(Set.of("good-event")));
    }

    @Test
    @DisplayName("sync: deletes expired detected_events in batches before Redis sync")
    void sync_deletesExpiredEventsBeforeSync() {
        // First batch returns 500 (full), second returns 0 (done)
        given(detectedEventPort.deleteExpiredBatch(any(), eq(500)))
                .willReturn(500)
                .willReturn(0);
        given(controllerPort.findAllActive()).willReturn(List.of());

        scheduler.sync();

        verify(detectedEventPort, org.mockito.Mockito.times(2)).deleteExpiredBatch(any(), eq(500));
    }

    @Test
    @DisplayName("sync: cleanup DB exception does not abort Redis reconciliation")
    void sync_cleanupException_doesNotAbortRediSync() {
        given(detectedEventPort.deleteExpiredBatch(any(), anyInt()))
                .willThrow(new RuntimeException("DB timeout"));
        ControllerEntity ctrl = controllerEntity(CTRL_ID);
        given(controllerPort.findAllActive()).willReturn(List.of(ctrl));
        given(detectedEventPort.findAllExternalIdsByControllerId(CTRL_ID)).willReturn(List.of("e1"));
        given(detectedEventPort.findExternalIdsByControllerIdSince(eq(CTRL_ID), any())).willReturn(List.of("e1"));

        scheduler.sync(); // must not throw

        verify(dedup).syncSeenEvents(eq(CTRL_ID), any(), any()); // Redis sync still ran
    }

    @Test
    @DisplayName("sync: cleanup runs before Redis sync — deleted IDs are absent from allDbIds")
    void sync_cleanupBeforeRedisSyncOrder() {
        ControllerEntity ctrl = controllerEntity(CTRL_ID);
        given(controllerPort.findAllActive()).willReturn(List.of(ctrl));
        given(detectedEventPort.findAllExternalIdsByControllerId(CTRL_ID)).willReturn(List.of("e1"));
        given(detectedEventPort.findExternalIdsByControllerIdSince(eq(CTRL_ID), any())).willReturn(List.of("e1"));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(detectedEventPort, dedup);

        scheduler.sync();

        inOrder.verify(detectedEventPort).deleteExpiredBatch(any(), anyInt());
        inOrder.verify(dedup).syncSeenEvents(any(), any(), any());
    }

    private ControllerEntity controllerEntity(UUID id) {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID()).telegramId(1L)
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
        return ControllerEntity.builder()
                .id(id).user(user)
                .bookmaker(BookmakerType.XBET)
                .url("https://1xstavka.ru/line/football/1")
                .type(ControllerType.TOURNAMENT)
                .isActive(true).isMuted(false)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
    }
}
