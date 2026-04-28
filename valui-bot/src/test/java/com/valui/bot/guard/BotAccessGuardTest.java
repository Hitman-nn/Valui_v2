package com.valui.bot.guard;

import com.valui.bot.keyboard.CallbackData;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.service.PlanLimitChecker;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BotAccessGuard — unit tests")
class BotAccessGuardTest {

    @Mock private PlanLimitChecker planLimitChecker;
    @Mock private AbsSender sender;

    private static final Long CHAT_ID = 42L;

    private BotAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BotAccessGuard(planLimitChecker);
    }

    // ─── guardAddController ───────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddController: within limit → no exception, no message sent")
    void guardAddController_withinLimit_silent() throws TelegramApiException {
        // checkControllerLimit does not throw — within limit

        assertThatCode(() -> guard.guardAddController(CHAT_ID, CHAT_ID, sender))
            .doesNotThrowAnyException();

        then(sender).should(never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("guardAddController: limit exceeded → throws and sends upgrade prompt")
    void guardAddController_exceeded_throwsAndSendsMessage() throws TelegramApiException {
        willThrow(new SubscriptionLimitExceededException("controllers", 3))
            .given(planLimitChecker).checkControllerLimit(CHAT_ID);
        given(planLimitChecker.getLimitInfo(CHAT_ID)).willReturn(freeLimits(3, 3));

        assertThatThrownBy(() -> guard.guardAddController(CHAT_ID, CHAT_ID, sender))
            .isInstanceOf(SubscriptionLimitExceededException.class);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        then(sender).should().execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("FREE", "3/3");
        assertThat(((InlineKeyboardMarkup) captor.getValue().getReplyMarkup())
            .getKeyboard().get(0).get(0).getCallbackData())
            .isEqualTo(CallbackData.PLANS_VIEW);
    }

    // ─── guardBookmakerAccess ─────────────────────────────────────────────────

    @Test
    @DisplayName("guardBookmakerAccess: bookmaker allowed → no exception")
    void guardBookmakerAccess_allowed_silent() throws TelegramApiException {
        assertThatCode(() -> guard.guardBookmakerAccess(CHAT_ID, CHAT_ID, "XBET", sender))
            .doesNotThrowAnyException();

        then(sender).should(never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("guardBookmakerAccess: bookmaker blocked → throws and sends prompt")
    void guardBookmakerAccess_blocked_throwsAndSendsMessage() throws TelegramApiException {
        willThrow(new SubscriptionLimitExceededException("Bookmaker 'OLIMP' not available on plan 'FREE'"))
            .given(planLimitChecker).checkBookmakerAccess(CHAT_ID, "OLIMP");
        given(planLimitChecker.getLimitInfo(CHAT_ID))
            .willReturn(freeLimits(1, 3));

        assertThatThrownBy(() -> guard.guardBookmakerAccess(CHAT_ID, CHAT_ID, "OLIMP", sender))
            .isInstanceOf(SubscriptionLimitExceededException.class);

        then(sender).should().execute(any(SendMessage.class));
    }

    // ─── guardAddFilter ───────────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddFilter: within filter limit → no exception")
    void guardAddFilter_withinLimit_silent() throws TelegramApiException {
        assertThatCode(() -> guard.guardAddFilter(CHAT_ID, CHAT_ID, sender))
            .doesNotThrowAnyException();

        then(sender).should(never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("guardAddFilter: filter limit exceeded → throws and sends prompt")
    void guardAddFilter_exceeded_throwsAndSendsMessage() throws TelegramApiException {
        willThrow(new SubscriptionLimitExceededException("filters", 1))
            .given(planLimitChecker).checkFilterLimit(CHAT_ID);
        given(planLimitChecker.getLimitInfo(CHAT_ID))
            .willReturn(freeLimitsWithFilter(0, 3, 1, 1));

        assertThatThrownBy(() -> guard.guardAddFilter(CHAT_ID, CHAT_ID, sender))
            .isInstanceOf(SubscriptionLimitExceededException.class);

        then(sender).should().execute(any(SendMessage.class));
    }

    // ─── TelegramApiException resilience ─────────────────────────────────────

    @Test
    @DisplayName("guard: if sending the prompt fails, original exception still propagates")
    void guard_sendFails_exceptionStillPropagates() throws TelegramApiException {
        willThrow(new SubscriptionLimitExceededException("controllers", 3))
            .given(planLimitChecker).checkControllerLimit(CHAT_ID);
        given(planLimitChecker.getLimitInfo(CHAT_ID)).willReturn(freeLimits(3, 3));
        given(sender.execute(any(SendMessage.class)))
            .willThrow(new TelegramApiException("network error"));

        assertThatThrownBy(() -> guard.guardAddController(CHAT_ID, CHAT_ID, sender))
            .isInstanceOf(SubscriptionLimitExceededException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static LimitInfoDto freeLimits(int used, int max) {
        return new LimitInfoDto(used, max, 0, 1, List.of("XBET"), 120, "FREE", null, 0);
    }

    private static LimitInfoDto freeLimitsWithFilter(int ctrlUsed, int ctrlMax,
                                                      int filterUsed, int filterMax) {
        return new LimitInfoDto(ctrlUsed, ctrlMax, filterUsed, filterMax,
            List.of("XBET"), 120, "FREE", null, 0);
    }
}
