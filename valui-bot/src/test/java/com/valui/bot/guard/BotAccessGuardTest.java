package com.valui.bot.guard;

import com.valui.bot.keyboard.CallbackData;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.api.PlanLimitFacade;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BotAccessGuard — unit tests")
class BotAccessGuardTest {

    @Mock private PlanLimitFacade planLimitFacade;
    @Mock private AbsSender sender;

    private static final Long CHAT_ID = 42L;

    private BotAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BotAccessGuard(planLimitFacade);
    }

    // ─── guardAddController ───────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddController: sufficient tokens → no exception, no message sent")
    void guardAddController_sufficientTokens_silent() throws TelegramApiException {
        assertThatCode(() -> guard.guardAddController(CHAT_ID, CHAT_ID, "XBET", sender))
            .doesNotThrowAnyException();

        then(sender).should(never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("guardAddController: insufficient tokens → throws and sends upgrade prompt")
    void guardAddController_insufficientTokens_throwsAndSendsMessage() throws TelegramApiException {
        willThrow(new InsufficientTokensException(5, 0))
            .given(planLimitFacade).debitForBkSlotIfNew(CHAT_ID, "XBET");
        given(planLimitFacade.getLimitInfo(CHAT_ID)).willReturn(limits(0, 200, 0));

        assertThatThrownBy(() -> guard.guardAddController(CHAT_ID, CHAT_ID, "XBET", sender))
            .isInstanceOf(InsufficientTokensException.class);

        then(sender).should().execute(any(SendMessage.class));
    }

    // ─── guardAddFilter ───────────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddFilter: sufficient tokens → no exception")
    void guardAddFilter_sufficientTokens_silent() throws TelegramApiException {
        assertThatCode(() -> guard.guardAddFilter(CHAT_ID, CHAT_ID, sender))
            .doesNotThrowAnyException();

        then(sender).should(never()).execute(any(SendMessage.class));
    }

    @Test
    @DisplayName("guardAddFilter: insufficient tokens → throws and sends prompt")
    void guardAddFilter_insufficientTokens_throwsAndSendsMessage() throws TelegramApiException {
        willThrow(new InsufficientTokensException(2, 0))
            .given(planLimitFacade).debitForControllerFilter(CHAT_ID);
        given(planLimitFacade.getLimitInfo(CHAT_ID)).willReturn(limits(0, 200, 0));

        assertThatThrownBy(() -> guard.guardAddFilter(CHAT_ID, CHAT_ID, sender))
            .isInstanceOf(InsufficientTokensException.class);

        then(sender).should().execute(any(SendMessage.class));
    }

    // ─── resilience ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("guard: if sending the prompt fails, original exception still propagates")
    void guard_sendFails_exceptionStillPropagates() throws TelegramApiException {
        willThrow(new InsufficientTokensException(5, 0))
            .given(planLimitFacade).debitForBkSlotIfNew(CHAT_ID, "XBET");
        given(planLimitFacade.getLimitInfo(CHAT_ID)).willReturn(limits(0, 200, 0));
        given(sender.execute(any(SendMessage.class)))
            .willThrow(new TelegramApiException("network error"));

        assertThatThrownBy(() -> guard.guardAddController(CHAT_ID, CHAT_ID, "XBET", sender))
            .isInstanceOf(InsufficientTokensException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static LimitInfoDto limits(int controllersUsed, int monthlyGrant, int tokenBalance) {
        return new LimitInfoDto(controllersUsed, Integer.MAX_VALUE, 0, Integer.MAX_VALUE,
            List.of("XBET"), 120, "FREE", null, tokenBalance, monthlyGrant, 0);
    }
}
