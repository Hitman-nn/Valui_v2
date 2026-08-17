package com.valui.monitor.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.scheduler.MonitorScheduler;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * REGRESSION: stopForChat used to remove only the calling chat plus one hardcoded
 * "notificationChatId" alternate — a controller subscribed across 3+ chats (the compound PK
 * (controller_id, chat_id) allows this) kept polling and notifying the chats beyond those two
 * after the owner called /stop.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ControllerServiceImpl.stopForChat")
class ControllerServiceImplStopForChatTest {

    @Mock ControllerPortService     controllerPort;
    @Mock DetectedEventPortService  detectedEventPort;
    @Mock UserService               userService;
    @Mock PlanLimitFacade           planLimitFacade;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock EventDeduplicationService dedup;
    @Mock MonitorScheduler          monitorScheduler;
    @Mock MonitorProperties         monitorProps;

    ControllerServiceImpl service;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final long TG_ID   = 111L;

    @BeforeEach
    void setUp() {
        service = new ControllerServiceImpl(controllerPort, detectedEventPort, userService,
                planLimitFacade, eventPublisher, dedup, monitorScheduler, monitorProps);

        UserEntity user = UserEntity.builder()
                .id(USER_ID).telegramId(TG_ID)
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));

        ControllerEntity ctrl = ControllerEntity.builder()
                .id(CTRL_ID).user(user)
                .bookmaker(BookmakerType.XBET).url("http://x")
                .type(ControllerType.TOURNAMENT)
                .isActive(true).isMuted(false)
                .build();
        given(controllerPort.findByIdAndUserId(CTRL_ID, USER_ID)).willReturn(Optional.of(ctrl));
    }

    private static ControllerSubscriptionEntity sub(long chatId) {
        return ControllerSubscriptionEntity.builder()
                .controllerId(CTRL_ID).chatId(chatId).userId(USER_ID).telegramId(TG_ID)
                .build();
    }

    @Test
    @DisplayName("Removes every active subscription for the controller, not just the calling chat plus notificationChatId")
    void removesAllChatsForController_notJustTwo() {
        // 3 chats subscribed to this controller — the calling chat, a "group" alt chat, and a
        // THIRD chat that the old two-chat-hardcoded logic would never have touched.
        given(controllerPort.findAllSubscriptions(CTRL_ID)).willReturn(
                List.of(sub(TG_ID), sub(-500L), sub(-999L)));
        given(controllerPort.hasActiveSubscriptions(CTRL_ID)).willReturn(false);

        service.stopForChat(CTRL_ID, TG_ID, TG_ID);

        ArgumentCaptor<Long> chatIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(controllerPort, times(3)).removeSubscription(any(), chatIdCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(chatIdCaptor.getAllValues())
                .containsExactlyInAnyOrder(TG_ID, -500L, -999L);
    }

    @Test
    @DisplayName("Deactivates the controller once no active subscriptions remain")
    void deactivatesWhenNoActiveSubscriptionsRemain() {
        given(controllerPort.findAllSubscriptions(CTRL_ID)).willReturn(List.of(sub(TG_ID)));
        given(controllerPort.hasActiveSubscriptions(CTRL_ID)).willReturn(false);

        service.stopForChat(CTRL_ID, TG_ID, TG_ID);

        verify(controllerPort).updateIsActive(CTRL_ID, false);
        verify(monitorScheduler).unscheduleController(CTRL_ID);
    }
}
