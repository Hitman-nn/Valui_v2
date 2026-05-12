package com.valui.notify.consumer;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.SportEventDetectedMessage;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dedup.TitleDedupCacheService;
import com.valui.notify.formatter.NotificationFormatter;
import com.valui.notify.log.NotificationLogService;
import com.valui.betting.cache.BetNotifCacheService;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.UserPortService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.service.GlobalFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SportEventConsumer — unit tests")
class SportEventConsumerTest {

    @Mock ControllerPortService          controllerPort;
    @Mock UserPortService                userPort;
    @Mock DetectedEventPortService       detectedEventPort;
    @Mock GlobalFilterService            globalFilterService;
    @Mock NotificationLogService         notificationLogService;
    @Mock NotificationFormatter          formatter;
    @Mock KafkaTemplate<String, Object>  kafkaTemplate;
    @Mock QuickAddCacheService           quickAddCacheService;
    @Mock BetNotifCacheService           betNotifCacheService;
    @Mock TitleDedupCacheService         titleDedupCache;

    @InjectMocks SportEventConsumer consumer;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final long TG_ID   = 42L;

    UserEntity       activeUser;
    ControllerEntity activeController;
    SportEventDetectedMessage event;
    NotificationLogEntity logEntry;

    @BeforeEach
    void setUp() {
        activeUser = UserEntity.builder()
                .id(USER_ID).telegramId(TG_ID).status(UserStatus.ACTIVE).build();

        activeController = ControllerEntity.builder()
                .id(CTRL_ID).bookmaker(BookmakerType.FONBET)
                .isActive(true).isMuted(false)
                .type(ControllerType.TOURNAMENT)
                .build();

        event = new SportEventDetectedMessage(
                UUID.randomUUID().toString(),
                CTRL_ID.toString(),
                USER_ID.toString(),
                TG_ID,
                TG_ID,
                "FONBET",
                "ext-match-1",
                "Spartak - CSKA",
                "https://fonbet.ru/1",
                Instant.now(), null);

        logEntry = NotificationLogEntity.builder()
                .id(UUID.randomUUID()).status(NotificationStatus.PENDING).build();

        given(controllerPort.findById(CTRL_ID)).willReturn(Optional.of(activeController));
        given(userPort.findById(USER_ID)).willReturn(Optional.of(activeUser));
        given(globalFilterService.findByUserId(USER_ID)).willReturn(List.of());
        given(detectedEventPort.findIdByControllerIdAndExternalId(any(), any()))
                .willReturn(Optional.empty());
        given(notificationLogService.createPending(any(), any(), any(), any())).willReturn(logEntry);
        given(formatter.buildTelegramMessage(any(), any()))
                .willReturn("🔔 *FONBET*\nSpartak - CSKA\nhttps://...");
        given(kafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        // Default: no dedup cache hit — events pass through normally
        given(titleDedupCache.computeKey(anyLong(), any(), any(), any())).willReturn("dedup-key");
        given(titleDedupCache.find(any())).willReturn(Optional.empty());
    }

    // ── filter rule ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("event matches regex filter → notification published")
    void filterRule_matches_publishes() {
        activeController.setFilterRule("spartak");

        consumer.onSportEventDetected(event);

        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("event does NOT match regex filter → dropped, no Kafka send")
    void filterRule_noMatch_drops() {
        activeController.setFilterRule("zenit");

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("null filter rule → event passes through")
    void filterRule_null_passes() {
        activeController.setFilterRule(null);

        consumer.onSportEventDetected(event);

        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("invalid regex in filterRule → treated as pass (event not silently blocked)")
    void filterRule_invalidRegex_passes() {
        activeController.setFilterRule("[unclosed");

        consumer.onSportEventDetected(event);

        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    // ── global filter ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("global filter drops event when title does NOT match the rule")
    void globalFilter_noMatch_drops() {
        given(globalFilterService.findByUserId(USER_ID)).willReturn(List.of("zenit"));

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("global filter passes event when title matches the rule")
    void globalFilter_matches_passes() {
        given(globalFilterService.findByUserId(USER_ID)).willReturn(List.of("spartak"));

        consumer.onSportEventDetected(event);

        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    // ── muted controller ──────────────────────────────────────────────────────

    @Test
    @DisplayName("muted controller → dropped, no log entry, no Kafka send")
    void mutedController_drops() {
        activeController.setIsMuted(true);

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
        verifyNoInteractions(notificationLogService);
    }

    // ── inactive controller ───────────────────────────────────────────────────

    @Test
    @DisplayName("inactive controller → dropped")
    void inactiveController_drops() {
        activeController.setIsActive(false);

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
    }

    // ── inactive user ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("banned user → dropped")
    void bannedUser_drops() {
        activeUser.setStatus(UserStatus.BANNED);

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
    }

    // ── published message ─────────────────────────────────────────────────────

    @Test
    @DisplayName("all checks pass → UserNotificationRequestMessage has correct fields")
    void allChecksPass_correctMessagePublished() {
        consumer.onSportEventDetected(event);

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                eq(KafkaTopics.USER_NOTIFICATIONS_PENDING),
                eq(USER_ID.toString()),
                valueCaptor.capture());

        UserNotificationRequestMessage msg =
                (UserNotificationRequestMessage) valueCaptor.getValue();
        assertThat(msg.channel()).isEqualTo(NotificationChannel.TELEGRAM.name());
        assertThat(msg.userId()).isEqualTo(USER_ID.toString());
        assertThat(msg.telegramId()).isEqualTo(TG_ID);
        assertThat(msg.notificationLogId()).isEqualTo(logEntry.getId().toString());
    }

    // ── duplicate suppression ─────────────────────────────────────────────────

    @Test
    @DisplayName("DataIntegrityViolationException → duplicate suppressed, no Kafka send")
    void duplicateNotification_suppressed() {
        given(notificationLogService.createPending(any(), any(), any(), any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        consumer.onSportEventDetected(event);

        verifyNoInteractions(kafkaTemplate);
    }

    // ── title-based dedup ─────────────────────────────────────────────────────

    @Test
    @DisplayName("dedup cache hit → edit-request sent, no new log entry, no createPending")
    void dedupCacheHit_sendsEditRequest_noNewLogEntry() {
        com.valui.notify.dedup.TitleDedupEntry existing =
                new com.valui.notify.dedup.TitleDedupEntry(
                        555,        // telegramMessageId
                        TG_ID,      // chatId
                        "old-bet",  // betKey
                        null);      // quickAddKey
        given(titleDedupCache.find(any())).willReturn(Optional.of(existing));

        consumer.onSportEventDetected(event);

        // Must forward an edit-request (editMessageId != null)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        com.valui.common.kafka.UserNotificationRequestMessage msg =
                (com.valui.common.kafka.UserNotificationRequestMessage) captor.getValue();
        assertThat(msg.editMessageId()).isEqualTo(555);
        assertThat(msg.notificationLogId()).isNull();

        // Must NOT create a new log entry
        verifyNoInteractions(notificationLogService);
    }

    @Test
    @DisplayName("dedup cache hit → bet Redis key updated with new URL")
    void dedupCacheHit_updatesBetCacheEntry() {
        com.valui.notify.dedup.TitleDedupEntry existing =
                new com.valui.notify.dedup.TitleDedupEntry(555, TG_ID, "old-bet", null);
        given(titleDedupCache.find(any())).willReturn(Optional.of(existing));

        consumer.onSportEventDetected(event);

        verify(betNotifCacheService).store(eq("old-bet"), any());
    }
}
