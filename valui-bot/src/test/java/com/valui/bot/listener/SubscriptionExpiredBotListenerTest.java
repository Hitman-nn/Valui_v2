package com.valui.bot.listener;

import com.valui.bot.i18n.BotMessageSource;
import com.valui.user.event.SubscriptionExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubscriptionExpiredBotListener — unit tests")
class SubscriptionExpiredBotListenerTest {

    @Mock private AbsSender bot;
    @Mock private BotMessageSource messageSource;

    private SubscriptionExpiredBotListener listener;

    private static final Long TELEGRAM_ID = 77L;
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(messageSource.getMessage(any(), anyLong(), any(Object[].class)))
            .willAnswer(inv -> "msg:" + inv.getArgument(0));
        given(messageSource.getMessage(any(), anyLong()))
            .willAnswer(inv -> "msg:" + inv.getArgument(0));
        listener = new SubscriptionExpiredBotListener(bot, messageSource);
    }

    @Test
    @DisplayName("onSubscriptionExpired: sends message to the correct chatId")
    void onExpired_sendsToCorrectChatId() throws TelegramApiException {
        SubscriptionExpiredEvent event = new SubscriptionExpiredEvent(USER_ID, TELEGRAM_ID, "PRO");

        listener.onSubscriptionExpired(event);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        then(bot).should().execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(String.valueOf(TELEGRAM_ID));
    }

    @Test
    @DisplayName("onSubscriptionExpired: uses subscription.expired message key with plan code arg")
    void onExpired_usesCorrectMessageKey() throws TelegramApiException {
        SubscriptionExpiredEvent event = new SubscriptionExpiredEvent(USER_ID, TELEGRAM_ID, "PRO");

        listener.onSubscriptionExpired(event);

        then(messageSource).should().getMessage(
            eq("subscription.expired"), eq(TELEGRAM_ID), eq("PRO"));
    }

    @Test
    @DisplayName("onSubscriptionExpired: message includes an inline keyboard")
    void onExpired_hasKeyboard() throws TelegramApiException {
        SubscriptionExpiredEvent event = new SubscriptionExpiredEvent(USER_ID, TELEGRAM_ID, "PREMIUM");

        listener.onSubscriptionExpired(event);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        then(bot).should().execute(captor.capture());
        assertThat(captor.getValue().getReplyMarkup()).isNotNull();
    }

    @Test
    @DisplayName("onSubscriptionExpired: TelegramApiException is swallowed (no rethrow)")
    void onExpired_telegramError_doesNotPropagate() throws TelegramApiException {
        given(bot.execute(any(SendMessage.class)))
            .willThrow(new TelegramApiException("network error"));
        SubscriptionExpiredEvent event = new SubscriptionExpiredEvent(USER_ID, TELEGRAM_ID, "PRO");

        assertThatCode(() -> listener.onSubscriptionExpired(event))
            .doesNotThrowAnyException();
    }
}
