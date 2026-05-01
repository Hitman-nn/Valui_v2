package com.valui.monitor.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.SportEventDetectedEvent;
import com.valui.monitor.outbox.OutboxEvent;
import com.valui.monitor.outbox.OutboxEventRepository;
import com.valui.monitor.outbox.OutboxSenderService;
import com.valui.monitor.scheduler.ControllerTaskExecutor.ParsedItem;
import com.valui.monitor.scheduler.ControllerTaskExecutor.TaskContext;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ControllerTaskExecutor — unit tests")
class ControllerTaskExecutorTest {

    @Mock ControllerPortService controllerPort;
    @Mock DetectedEventPortService detectedEventPort;
    @Mock ParserFactory parserFactory;
    @Mock ApplicationEventPublisher events;
    @Mock MonitorProperties props;
    @Mock EventDeduplicationService dedup;
    @Mock OutboxEventRepository outboxRepo;
    @Mock OutboxSenderService outboxSenderService;

    @InjectMocks ControllerTaskExecutor executor;

    static final UUID CTRL_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();
    static final long TG_ID   = 99L;
    static final String TOURNAMENT_ID = "12345";
    static final String XBET_URL =
            "https://1xstavka.ru/line/football/" + TOURNAMENT_ID;

    UserEntity user;
    ControllerEntity controller;
    TaskContext ctx;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
                .id(USER_ID).telegramId(TG_ID)
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
        controller = ControllerEntity.builder()
                .id(CTRL_ID).user(user)
                .bookmaker(BookmakerType.XBET).url(XBET_URL)
                .type(ControllerType.TOURNAMENT)
                .isActive(true).isMuted(false)
                // lastCheckedAt != null → not a warmup run → events will be published
                .lastCheckedAt(OffsetDateTime.now().minusMinutes(1))
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        ctx = new TaskContext(CTRL_ID, USER_ID, TG_ID,
                BookmakerType.XBET, XBET_URL, TOURNAMENT_ID, null,
                ControllerType.TOURNAMENT);

        // Default: one active subscription for the controller
        ControllerSubscriptionEntity sub = ControllerSubscriptionEntity.builder()
                .controllerId(CTRL_ID)
                .chatId(TG_ID)
                .userId(USER_ID)
                .telegramId(TG_ID)
                .build();
        given(controllerPort.findActiveSubscriptions(CTRL_ID)).willReturn(List.of(sub));

        given(props.getDefaultPollIntervalSec()).willReturn(60);
        given(outboxSenderService.buildOutboxEvent(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(OutboxEvent.builder().externalEventId("stub").build());
        given(outboxRepo.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    // ── persistNewEvents: new event ───────────────────────────────────────────

    @Test
    @DisplayName("persistNewEvents: new match is saved and SportEventDetectedEvent published")
    void persistNewEvents_newMatch_savesAndPublishes() {
        ParsedItem item = new ParsedItem("evt1", "Zenit - CSKA", "https://1xstavka.ru/evt1");
        given(controllerPort.findById(CTRL_ID)).willReturn(Optional.of(controller));
        given(dedup.claimIfNew(CTRL_ID, "evt1")).willReturn(true);
        given(detectedEventPort.insertIfAbsent(any(), any(), any(), any(), any())).willReturn(true);
        given(controllerPort.save(any())).willReturn(controller);

        int count = executor.persistNewEvents(ctx, List.of(item));

        assertThat(count).isEqualTo(1);

        ArgumentCaptor<SportEventDetectedEvent> captor =
                ArgumentCaptor.forClass(SportEventDetectedEvent.class);
        verify(events).publishEvent(captor.capture());
        SportEventDetectedEvent published = captor.getValue();
        assertThat(published.externalEventId()).isEqualTo("evt1");
        assertThat(published.bookmaker()).isEqualTo(BookmakerType.XBET);
        assertThat(published.userId()).isEqualTo(USER_ID);

        verify(controllerPort).save(argThat((ControllerEntity c) -> c.getLastCheckedAt() != null));
    }

    @Test
    @DisplayName("persistNewEvents: duplicate match is skipped, no event published")
    void persistNewEvents_duplicateMatch_noEvent() {
        ParsedItem item = new ParsedItem("evt1", "Zenit - CSKA", null);
        given(controllerPort.findById(CTRL_ID)).willReturn(Optional.of(controller));
        given(dedup.claimIfNew(CTRL_ID, "evt1")).willReturn(false);
        given(controllerPort.save(any())).willReturn(controller);

        int count = executor.persistNewEvents(ctx, List.of(item));

        assertThat(count).isZero();
        verify(events, never()).publishEvent(any());
        verify(detectedEventPort, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("persistNewEvents: mixed list — only non-duplicates persisted")
    void persistNewEvents_mixed_persistsOnlyNew() {
        ParsedItem old = new ParsedItem("evtOld", "A - B", null);
        ParsedItem fresh = new ParsedItem("evtNew", "C - D", null);
        given(controllerPort.findById(CTRL_ID)).willReturn(Optional.of(controller));
        given(dedup.claimIfNew(CTRL_ID, "evtOld")).willReturn(false);
        given(dedup.claimIfNew(CTRL_ID, "evtNew")).willReturn(true);
        given(detectedEventPort.insertIfAbsent(any(), any(), any(), any(), any())).willReturn(true);
        given(controllerPort.save(any())).willReturn(controller);

        int count = executor.persistNewEvents(ctx, List.of(old, fresh));

        assertThat(count).isEqualTo(1);
        verify(events, times(1)).publishEvent(any(SportEventDetectedEvent.class));
    }

    @Test
    @DisplayName("persistNewEvents: empty list — only lastCheckedAt updated, no event")
    void persistNewEvents_emptyList_updatesTimestamp() {
        executor.persistNewEvents(ctx, List.of());

        verify(controllerPort).updateLastCheckedAt(eq(CTRL_ID), any());
        verify(events, never()).publishEvent(any());
    }

    // ── fetch ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetch: successful fetchMatches returns ParsedItems")
    void fetch_success_returnsParsedItems() {
        BookmakerParser parser = mock(BookmakerParser.class);
        given(parserFactory.getParser(eq(BookmakerType.XBET))).willReturn(parser);
        given(parser.fetchMatches(TOURNAMENT_ID)).willReturn(
                ParseResult.ok(List.of(
                        new ParsedMatchDto("m1", "A - B", TOURNAMENT_ID, "https://...", Instant.now(), false)
                ), 50L));

        List<ParsedItem> items = executor.fetch(ctx);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("m1");
    }

    @Test
    @DisplayName("fetch: parser error returns empty list (no exception)")
    void fetch_parserError_returnsEmpty() {
        BookmakerParser parser = mock(BookmakerParser.class);
        given(parserFactory.getParser(eq(BookmakerType.XBET))).willReturn(parser);
        given(parser.fetchMatches(TOURNAMENT_ID)).willReturn(ParseResult.error("cb open"));

        List<ParsedItem> items = executor.fetch(ctx);

        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("fetch: no IDs in URL returns empty list")
    void fetch_noIdsInUrl_returnsEmpty() {
        TaskContext badCtx = new TaskContext(CTRL_ID, USER_ID, TG_ID,
                BookmakerType.XBET, XBET_URL, null, null,
                ControllerType.TOURNAMENT);

        List<ParsedItem> items = executor.fetch(badCtx);

        assertThat(items).isEmpty();
        verify(parserFactory, never()).getParser(any(BookmakerType.class));
    }
}
