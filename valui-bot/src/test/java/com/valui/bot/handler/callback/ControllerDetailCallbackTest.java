package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.api.DetectedEventPortService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Regression test for a bug caught during manual verification: the boolean handed to
 * {@code buildKeyboard}/{@code buildDetailKeyboard} used to be derived straight from
 * {@code ctx.isGroupChat()} with the formula {@code isOwner = !isGroupChat || ...}, which meant
 * "not physically in a group" (i.e. any DM) always short-circuited to {@code isOwner = true} —
 * showing stop/mute/filter management buttons even for a linked GROUP's controllers the caller
 * doesn't own, browsed from DM. These tests pin the corrected behaviour: management only shows
 * in a physical group (real ownership check) or in DM with nothing picked yet (legacy personal
 * list, ownership implied); never in DM once a linked group is selected.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControllerDetailCallback — management buttons never leak into DM group browsing")
class ControllerDetailCallbackTest {

    @Mock ControllerService controllerService;
    @Mock BotMessageSource messageSource;
    @Mock DetectedEventPortService detectedEventPort;
    @Mock BettingChatResolver chatResolver;
    @Mock WizardMessageTracker tracker;
    @Mock AbsSender sender;

    private static final long GROUP_CHAT_ID = -1001111111111L;
    private static final long DM_CHAT_ID    = 777L;
    private static final long OWNER_ID      = 777L;
    private static final int  MESSAGE_ID    = 9;
    private static final UUID CONTROLLER_ID = UUID.randomUUID();

    private ControllerDetailCallback callback() {
        return new ControllerDetailCallback(controllerService, messageSource, detectedEventPort, chatResolver);
    }

    private void stubCommon(long ownerTelegramId) {
        given(controllerService.getControllerForChat(eq(CONTROLLER_ID), anyLong()))
                .willReturn(controller(ownerTelegramId));
        given(detectedEventPort.findRecentByControllerId(eq(CONTROLLER_ID), any()))
                .willReturn(emptyPage());
    }

    @Nested
    @DisplayName("Physical group chat")
    class GroupChat {

        @Test
        @DisplayName("Owner sees management buttons")
        void ownerSeesManagement() {
            stubCommon(OWNER_ID);
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);

            callback().handle(ctx(GROUP_CHAT_ID, OWNER_ID));

            assertThat(hasManagementButtons()).isTrue();
        }

        @Test
        @DisplayName("Non-owner does NOT see management buttons")
        void nonOwnerHidesManagement() {
            stubCommon(999L); // someone else owns it
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);

            callback().handle(ctx(GROUP_CHAT_ID, OWNER_ID));

            assertThat(hasManagementButtons()).isFalse();
        }
    }

    @Nested
    @DisplayName("DM, nothing picked yet (legacy personal list)")
    class DmUnresolved {

        @Test
        @DisplayName("Owner of their own personal controller still sees management buttons")
        void ownPersonalController_stillManageable() {
            stubCommon(OWNER_ID);
            given(chatResolver.resolveOrPhysical(any())).willReturn(DM_CHAT_ID);
            given(chatResolver.isResolved(any())).willReturn(false);

            callback().handle(ctx(DM_CHAT_ID, OWNER_ID));

            assertThat(hasManagementButtons()).isTrue();
        }
    }

    @Nested
    @DisplayName("DM, linked group selected — the case that was broken")
    class DmResolvedToGroup {

        @Test
        @DisplayName("Even the true owner does NOT see management buttons (browse+bet only, per product decision)")
        void trueOwner_stillNoManagementInDm() {
            stubCommon(OWNER_ID); // caller genuinely owns this controller
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);
            given(chatResolver.isResolved(any())).willReturn(true);

            callback().handle(ctx(DM_CHAT_ID, OWNER_ID));

            assertThat(hasManagementButtons()).isFalse();
        }

        @Test
        @DisplayName("Non-owner naturally also does not see management buttons")
        void nonOwner_noManagementInDm() {
            stubCommon(999L);
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);
            given(chatResolver.isResolved(any())).willReturn(true);

            callback().handle(ctx(DM_CHAT_ID, OWNER_ID));

            assertThat(hasManagementButtons()).isFalse();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean hasManagementButtons() {
        ArgumentCaptor<InlineKeyboardMarkup> kb = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(tracker).replaceAndTrack(eq(sender), anyLong(), eq(MESSAGE_ID), anyString(), kb.capture());
        return kb.getValue().getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> "🛑 Остановить".equals(b.getText()));
    }

    private static Page<DetectedEventEntity> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private static ControllerDto controller(long ownerTelegramId) {
        return new ControllerDto(CONTROLLER_ID, "FONBET", "https://fonbet.ru/x", "Лига чемпионов",
                null, false, true, null, null, 3, ControllerType.SPORT, GROUP_CHAT_ID, ownerTelegramId, 60, null);
    }

    private BotUpdateContext ctx(long chatId, long fromId) {
        Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);

        CallbackQuery cbq = new CallbackQuery();
        cbq.setId("cb-1");
        cbq.setData("CTRL:DETAIL:" + CONTROLLER_ID);
        cbq.setMessage(message);

        Update update = new Update();
        update.setCallbackQuery(cbq);

        return new BotUpdateContext(update, chatId, fromId, "testuser",
                new UserBotSession(), null, sender, tracker);
    }
}
