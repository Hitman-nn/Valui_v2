package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.state.UserBotSession;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.ValuiException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.quickadd.QuickAddCacheService;
import com.valui.user.quickadd.QuickAddData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickAddControllerCallback — unit tests")
class QuickAddControllerCallbackTest {

    @Mock QuickAddCacheService quickAddCacheService;
    @Mock ControllerService controllerService;
    @Mock AbsSender sender;

    @InjectMocks QuickAddControllerCallback callback;

    private static final long   CHAT_ID    = 42L;
    private static final int    MESSAGE_ID = 999;
    private static final String CB_ID      = "cb-123";
    private static final String CACHE_KEY  = UUID.randomUUID().toString();
    private static final QuickAddData DATA =
            new QuickAddData("https://fonbet.ru/tournaments/1", "FONBET", "Лига чемпионов");

    // ── success ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Success: adds controller, sends toast, replaces button with ✅")
    void success_addsController_showsToast_replacesButton() throws Exception {
        given(quickAddCacheService.find(CACHE_KEY)).willReturn(Optional.of(DATA));
        given(controllerService.addController(any(), anyLong(), anyLong()))
                .willReturn(mock(ControllerDto.class));

        callback.handle(ctx("QADD:" + CACHE_KEY));

        // Toast with "добавлен" (case-insensitive: "✅ Контроллер добавлен!")
        ArgumentCaptor<AnswerCallbackQuery> toastCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(sender, atLeastOnce()).execute(toastCaptor.capture());
        assertThat(toastCaptor.getValue().getText()).containsIgnoringCase("добавлен");

        // Keyboard replaced with "✅ Добавлено" button
        ArgumentCaptor<EditMessageReplyMarkup> editCaptor = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
        verify(sender).execute(editCaptor.capture());
        EditMessageReplyMarkup edit = editCaptor.getValue();
        assertThat(edit.getMessageId()).isEqualTo(MESSAGE_ID);
        String doneButtonText = edit.getReplyMarkup()
                .getKeyboard().get(0).get(0).getText();
        assertThat(doneButtonText).contains("✅");
        assertThat(edit.getReplyMarkup().getKeyboard().get(0).get(0).getCallbackData())
                .isEqualTo(CallbackData.NOOP);
    }

    // ── duplicate ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate (409): shows 'Уже добавлен' toast, still replaces button")
    void duplicate_showsAlreadyAdded_replacesButton() throws Exception {
        given(quickAddCacheService.find(CACHE_KEY)).willReturn(Optional.of(DATA));
        given(controllerService.addController(any(), anyLong(), anyLong()))
                .willThrow(new ValuiException("Controller already exists", 409));

        callback.handle(ctx("QADD:" + CACHE_KEY));

        ArgumentCaptor<AnswerCallbackQuery> toastCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(sender, atLeastOnce()).execute(toastCaptor.capture());
        assertThat(toastCaptor.getValue().getText()).contains("Уже добавлен");

        verify(sender).execute(any(EditMessageReplyMarkup.class));
    }

    // ── plan limit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Plan limit exceeded: shows plan-upgrade toast, button stays (no EditMessage)")
    void limitExceeded_showsUpgradeToast_buttonUnchanged() throws Exception {
        given(quickAddCacheService.find(CACHE_KEY)).willReturn(Optional.of(DATA));
        given(controllerService.addController(any(), anyLong(), anyLong()))
                .willThrow(new SubscriptionLimitExceededException("controllers", 5));

        callback.handle(ctx("QADD:" + CACHE_KEY));

        ArgumentCaptor<AnswerCallbackQuery> toastCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(sender, atLeastOnce()).execute(toastCaptor.capture());
        assertThat(toastCaptor.getValue().getText()).contains("токенов");

        // Button must NOT be replaced — user may upgrade and return to this notification
        verify(sender, never()).execute(any(EditMessageReplyMarkup.class));
    }

    // ── cache miss ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache miss: shows 'устарела' toast, keyboard removed")
    void cacheMiss_showsExpiredToast_removesKeyboard() throws Exception {
        given(quickAddCacheService.find(any())).willReturn(Optional.empty());

        callback.handle(ctx("QADD:old-key"));

        ArgumentCaptor<AnswerCallbackQuery> toastCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(sender, atLeastOnce()).execute(toastCaptor.capture());
        assertThat(toastCaptor.getValue().getText()).contains("устарел");

        verifyNoInteractions(controllerService);

        // Keyboard should be removed (empty rows)
        ArgumentCaptor<EditMessageReplyMarkup> editCaptor = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
        verify(sender).execute(editCaptor.capture());
        assertThat(editCaptor.getValue().getReplyMarkup().getKeyboard()).isEmpty();
    }

    // ── callbackPrefix ────────────────────────────────────────────────────────

    @Test
    @DisplayName("callbackPrefix returns QADD: prefix")
    void callbackPrefix_isQadd() {
        assertThat(callback.callbackPrefix()).isEqualTo("QADD:");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BotUpdateContext ctx(String callbackData) {
        Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        message.setChat(chat);

        CallbackQuery cbq = new CallbackQuery();
        cbq.setId(CB_ID);
        cbq.setData(callbackData);
        cbq.setMessage(message);

        Update update = new Update();
        update.setCallbackQuery(cbq);

        return new BotUpdateContext(update, CHAT_ID, CHAT_ID, "testuser",
                new UserBotSession(), null, sender);
    }
}
