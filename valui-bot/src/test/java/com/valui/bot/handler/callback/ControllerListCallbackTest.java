package com.valui.bot.handler.callback;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.service.BetDmLinkService;
import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.callback.betting.BettingChatResolver;
import com.valui.bot.config.BotProperties;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.service.ControllerSortPreferenceService;
import com.valui.bot.service.WizardMessageTracker;
import com.valui.bot.state.UserBotSession;
import com.valui.common.domain.ControllerType;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Verifies that DM browsing of a linked group's controllers shows exactly the same data a
 * group member would see physically in that group (the crux of the DM betting-from-controllers
 * feature) — and that DM with nothing picked yet still falls back to the caller's own list,
 * unaffected.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControllerListCallback — group vs. DM parity")
class ControllerListCallbackTest {

    @Mock ControllerService controllerService;
    @Mock ControllerSortPreferenceService sortPreference;
    @Mock BettingChatResolver chatResolver;
    @Mock BetDmLinkService betDmLinkService;
    @Mock WizardMessageTracker tracker;
    @Mock AbsSender sender;

    private static final long GROUP_CHAT_ID   = -1001111111111L;
    private static final long OTHER_GROUP_ID  = -1002222222222L;
    private static final long DM_CHAT_ID      = 555L;
    private static final long FROM_ID         = 555L;
    private static final int  MESSAGE_ID      = 42;

    private ControllerListCallback callback() {
        return new ControllerListCallback(controllerService,
                new BotProperties(null, null, null, null, null, null, null, null, 30),
                sortPreference, chatResolver, betDmLinkService);
    }

    @Nested
    @DisplayName("Physical group chat")
    class GroupChat {

        @Test
        @DisplayName("Shows getGroupControllers(groupChatId) — the group's full list")
        void showsFullGroupList() {
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);
            given(sortPreference.load(GROUP_CHAT_ID)).willReturn("DATE");
            given(controllerService.getGroupControllers(GROUP_CHAT_ID)).willReturn(List.of(controller(true)));

            callback().handle(ctx(GROUP_CHAT_ID, GROUP_CHAT_ID, CallbackData.CTRL_LIST));

            verify(controllerService).getGroupControllers(GROUP_CHAT_ID);
            verify(controllerService, never()).getUserControllersForChat(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("DM with a linked group already selected")
    class DmResolved {

        @Test
        @DisplayName("Shows getGroupControllers(selectedChatId) — identical call to the group view")
        void showsSameGroupListAsPhysicalGroup() {
            given(chatResolver.isResolved(any())).willReturn(true);
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);
            given(sortPreference.load(GROUP_CHAT_ID)).willReturn("DATE");
            given(controllerService.getGroupControllers(GROUP_CHAT_ID)).willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            // Same service call, same chatId argument, as the physical-group case above —
            // this is what guarantees DM and group show identical data.
            verify(controllerService).getGroupControllers(GROUP_CHAT_ID);
            verify(controllerService, never()).getUserControllersForChat(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Does not add the '👥 Контроллеры группы' switch button once a chat is picked")
        void noSwitchButtonOnceResolved() {
            given(chatResolver.isResolved(any())).willReturn(true);
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID);
            given(sortPreference.load(GROUP_CHAT_ID)).willReturn("DATE");
            given(controllerService.getGroupControllers(GROUP_CHAT_ID)).willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            ArgumentCaptor<InlineKeyboardMarkup> kb = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(tracker).replaceAndTrack(eq(sender), eq(DM_CHAT_ID), eq(MESSAGE_ID), anyString(), kb.capture());
            boolean hasSwitchButton = kb.getValue().getKeyboard().stream().flatMap(List::stream)
                    .anyMatch(b -> "👥 Контроллеры группы".equals(b.getText()));
            assertThat(hasSwitchButton).isFalse();
        }
    }

    @Nested
    @DisplayName("DM with nothing picked yet, and zero or several linked groups")
    class DmUnresolved {

        @Test
        @DisplayName("No links: falls back to the caller's own controllers — unaffected by the new feature")
        void fallsBackToPersonalList() {
            given(betDmLinkService.listLinks(FROM_ID)).willReturn(List.of());
            given(chatResolver.isResolved(any())).willReturn(false);
            given(chatResolver.resolveOrPhysical(any())).willReturn(DM_CHAT_ID);
            given(sortPreference.load(DM_CHAT_ID)).willReturn("DATE");
            given(controllerService.getUserControllersForChat(FROM_ID, DM_CHAT_ID))
                    .willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            verify(controllerService).getUserControllersForChat(FROM_ID, DM_CHAT_ID);
            verify(controllerService, never()).getGroupControllers(anyLong());
            verify(chatResolver, never()).select(any(), anyLong());
        }

        @Test
        @DisplayName("No links: offers the '👥 Контроллеры группы' switch button")
        void offersSwitchButton() {
            given(betDmLinkService.listLinks(FROM_ID)).willReturn(List.of());
            given(chatResolver.isResolved(any())).willReturn(false);
            given(chatResolver.resolveOrPhysical(any())).willReturn(DM_CHAT_ID);
            given(sortPreference.load(DM_CHAT_ID)).willReturn("DATE");
            given(controllerService.getUserControllersForChat(FROM_ID, DM_CHAT_ID))
                    .willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            ArgumentCaptor<InlineKeyboardMarkup> kb = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(tracker).replaceAndTrack(eq(sender), eq(DM_CHAT_ID), eq(MESSAGE_ID), anyString(), kb.capture());
            boolean hasSwitchButton = kb.getValue().getKeyboard().stream().flatMap(List::stream)
                    .anyMatch(b -> "👥 Контроллеры группы".equals(b.getText()));
            assertThat(hasSwitchButton).isTrue();
        }

        @Test
        @DisplayName("2+ links: still falls back to personal list (nothing to pick unambiguously)")
        void multipleLinks_stillFallsBack() {
            given(betDmLinkService.listLinks(FROM_ID)).willReturn(List.of(
                    new BetDmLinkDto(GROUP_CHAT_ID, "Group A"), new BetDmLinkDto(OTHER_GROUP_ID, "Group B")));
            given(chatResolver.isResolved(any())).willReturn(false);
            given(chatResolver.resolveOrPhysical(any())).willReturn(DM_CHAT_ID);
            given(sortPreference.load(DM_CHAT_ID)).willReturn("DATE");
            given(controllerService.getUserControllersForChat(FROM_ID, DM_CHAT_ID))
                    .willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            verify(chatResolver, never()).select(any(), anyLong());
            verify(controllerService).getUserControllersForChat(FROM_ID, DM_CHAT_ID);
        }
    }

    @Nested
    @DisplayName("DM with exactly one linked group — the bug report this fixes")
    class DmSingleLink {

        @Test
        @DisplayName("Auto-selects the single linked group and shows its FULL list — no extra tap, no personal-only fallback")
        void autoSelectsSingleLink_showsFullGroupList() {
            given(betDmLinkService.listLinks(FROM_ID))
                    .willReturn(List.of(new BetDmLinkDto(GROUP_CHAT_ID, "Дружеский тотализатор")));
            // Mimics the real (Redis-backed) resolver's state change: unresolved on the first
            // check (before select()), resolved on every check after.
            given(chatResolver.isResolved(any())).willReturn(false, true);
            given(chatResolver.resolveOrPhysical(any())).willReturn(GROUP_CHAT_ID); // resolved AFTER select()
            given(sortPreference.load(GROUP_CHAT_ID)).willReturn("DATE");
            given(controllerService.getGroupControllers(GROUP_CHAT_ID)).willReturn(List.of(controller(true)));

            callback().handle(ctx(DM_CHAT_ID, DM_CHAT_ID, CallbackData.CTRL_LIST));

            verify(chatResolver).select(any(), eq(GROUP_CHAT_ID));
            verify(controllerService).getGroupControllers(GROUP_CHAT_ID);
            verify(controllerService, never()).getUserControllersForChat(anyLong(), anyLong());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ControllerDto controller(boolean active) {
        return new ControllerDto(UUID.randomUUID(), "FONBET", "https://fonbet.ru/x", "Лига чемпионов",
                null, false, active, null, null, 3, ControllerType.TOURNAMENT, GROUP_CHAT_ID, FROM_ID, 60, null);
    }

    private BotUpdateContext ctx(long chatId, long fromId, String callbackData) {
        Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);

        CallbackQuery cbq = new CallbackQuery();
        cbq.setId("cb-1");
        cbq.setData(callbackData);
        cbq.setMessage(message);

        Update update = new Update();
        update.setCallbackQuery(cbq);

        return new BotUpdateContext(update, chatId, fromId, "testuser",
                new UserBotSession(), null, sender, tracker);
    }
}
