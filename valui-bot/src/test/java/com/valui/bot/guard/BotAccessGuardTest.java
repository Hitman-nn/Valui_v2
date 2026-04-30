package com.valui.bot.guard;

import com.valui.common.exception.InsufficientTokensException;
import com.valui.user.api.PlanLimitFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotAccessGuard — unit tests")
class BotAccessGuardTest {

    @Mock private PlanLimitFacade planLimitFacade;

    private static final Long USER_ID = 42L;

    private BotAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BotAccessGuard(planLimitFacade);
    }

    // ─── guardAddController ───────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddController: sufficient tokens → no exception")
    void guardAddController_sufficientTokens_silent() {
        assertThatCode(() -> guard.guardAddController(USER_ID, "XBET"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("guardAddController: insufficient tokens → throws InsufficientTokensException")
    void guardAddController_insufficientTokens_throws() {
        willThrow(new InsufficientTokensException(5, 0))
            .given(planLimitFacade).debitForBkSlotIfNew(USER_ID, "XBET");

        assertThatThrownBy(() -> guard.guardAddController(USER_ID, "XBET"))
            .isInstanceOf(InsufficientTokensException.class);
    }

    // ─── guardAddFilter ───────────────────────────────────────────────────────

    @Test
    @DisplayName("guardAddFilter: sufficient tokens → no exception")
    void guardAddFilter_sufficientTokens_silent() {
        assertThatCode(() -> guard.guardAddFilter(USER_ID))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("guardAddFilter: insufficient tokens → throws InsufficientTokensException")
    void guardAddFilter_insufficientTokens_throws() {
        willThrow(new InsufficientTokensException(2, 0))
            .given(planLimitFacade).debitForControllerFilter(USER_ID);

        assertThatThrownBy(() -> guard.guardAddFilter(USER_ID))
            .isInstanceOf(InsufficientTokensException.class);
    }
}
