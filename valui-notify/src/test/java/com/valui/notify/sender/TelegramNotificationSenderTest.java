package com.valui.notify.sender;

import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.ratelimit.TelegramRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramNotificationSender — unit tests")
class TelegramNotificationSenderTest {

    @Mock AbsSender         absSender;
    @Mock TelegramRateLimiter rateLimiter;
    @InjectMocks TelegramNotificationSender sender;

    static final long CHAT_ID = 123456L;
    static final String TEXT  = "🔔 *FONBET*\nSpartak - CSKA\nhttps://fonbet.ru/1";

    // ── rate limit: pass ──────────────────────────────────────────────────────

    @Test
    @DisplayName("rate limit allows → message sent once")
    void rateLimitAllows_sendsOnce() throws Exception {
        given(rateLimiter.tryAcquire(CHAT_ID)).willReturn(true);

        sender.send(CHAT_ID, TEXT);

        verify(absSender).execute(any(SendMessage.class));
    }

    // ── rate limit: first deny, second allow ──────────────────────────────────

    @Test
    @DisplayName("first tryAcquire denied, second allowed after backoff → message sent")
    void rateLimitFirstDenied_secondAllowed_sends() throws Exception {
        given(rateLimiter.tryAcquire(CHAT_ID))
                .willReturn(false) // first check
                .willReturn(true); // after sleep

        sender.send(CHAT_ID, TEXT);

        verify(absSender).execute(any(SendMessage.class));
        verify(rateLimiter, times(2)).tryAcquire(CHAT_ID);
    }

    // ── rate limit: both denied ───────────────────────────────────────────────

    @Test
    @DisplayName("both tryAcquire calls denied → throws RuntimeException, no send")
    void rateLimitBothDenied_throws() {
        given(rateLimiter.tryAcquire(CHAT_ID)).willReturn(false);

        assertThatThrownBy(() -> sender.send(CHAT_ID, TEXT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rate limit");

        verifyNoInteractions(absSender);
    }

    // ── null chatId ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("null chatId → IllegalArgumentException before any send")
    void nullChatId_throws() {
        assertThatThrownBy(() -> sender.send(null, TEXT))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(absSender);
        verifyNoInteractions(rateLimiter);
    }

    // ── Telegram 429 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Telegram 429 on first attempt → retries once more")
    void telegram429_retriesOnce() throws Exception {
        given(rateLimiter.tryAcquire(CHAT_ID)).willReturn(true);
        TelegramApiRequestException ex429 = mock(TelegramApiRequestException.class);
        given(ex429.getErrorCode()).willReturn(429);
        given(absSender.execute(any(SendMessage.class)))
                .willThrow(ex429)
                .willReturn(null); // second attempt succeeds

        sender.send(CHAT_ID, TEXT);

        verify(absSender, times(2)).execute(any(SendMessage.class));
    }

    // ── editNotification ──────────────────────────────────────────────────────

    @Test
    @DisplayName("editNotification → EditMessageText sent to Telegram API")
    void editNotification_sendsEditMessageText() throws Exception {
        UserNotificationRequestMessage request = editRequest(null, null);

        sender.editNotification(CHAT_ID, 42, request);

        verify(absSender).execute(any(EditMessageText.class));
    }

    @Test
    @DisplayName("editNotification on TelegramApiRequestException → logs and returns, no throw")
    void editNotification_apiException_swallowed() throws Exception {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        given(ex.getErrorCode()).willReturn(400);
        given(ex.getMessage()).willReturn("message not modified");
        willThrow(ex).given(absSender).execute(any(EditMessageText.class));

        sender.editNotification(CHAT_ID, 42, editRequest(null, null));
        // no exception propagated
    }

    @Test
    @DisplayName("editNotification on generic exception → logs and returns, no throw")
    void editNotification_genericException_swallowed() throws Exception {
        willThrow(new RuntimeException("Redis down")).given(absSender).execute(any(EditMessageText.class));

        sender.editNotification(CHAT_ID, 42, editRequest(null, null));
        // no exception propagated
    }

    @Test
    @DisplayName("editNotification with betKey → keyboard attached to EditMessageText")
    void editNotification_withBetKey_attachesKeyboard() throws Exception {
        UserNotificationRequestMessage request = editRequest("bet-key-123", null);

        sender.editNotification(CHAT_ID, 42, request);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(absSender).execute(captor.capture());
        assertThat(captor.getValue().getReplyMarkup()).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static UserNotificationRequestMessage editRequest(String betKey, String quickAddKey) {
        return new UserNotificationRequestMessage(
                null,
                String.valueOf(CHAT_ID),
                CHAT_ID,
                "TELEGRAM",
                TEXT,
                UUID.randomUUID().toString(),
                quickAddKey,
                null,
                betKey,
                null,
                42);
    }
}
