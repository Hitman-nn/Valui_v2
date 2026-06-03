package com.valui.user.service;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.event.UserControllersPausedEvent;
import com.valui.user.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenLedgerServiceImpl — unit tests")
class TokenLedgerServiceImplTest {

    @Mock private UserRepository                   userRepository;
    @Mock private ControllerRepository             controllerRepository;
    @Mock private GlobalFilterRepository           globalFilterRepository;
    @Mock private ControllerSubscriptionRepository subscriptionRepository;
    @Mock private TokenActionCostRepository        costRepository;
    @Mock private TokenTransactionRepository       txRepository;
    @Mock private ApplicationEventPublisher        eventPublisher;
    @Mock private EntityManager                    em;

    @InjectMocks private TokenLedgerServiceImpl service;

    private static final UUID   USER_ID     = UUID.randomUUID();
    private static final long   TELEGRAM_ID = 55086685L;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
            .id(USER_ID)
            .telegramId(TELEGRAM_ID)
            .tokenBalance(100)
            .build();
        given(em.find(eq(UserEntity.class), eq(USER_ID), eq(LockModeType.PESSIMISTIC_WRITE), anyMap()))
            .willReturn(user);
        given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(txRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    // ─── getBalance ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBalance(UUID): returns user balance")
    void getBalance_byUuid_returnsBalance() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        assertThat(service.getBalance(USER_ID)).isEqualTo(100);
    }

    @Test
    @DisplayName("getBalance(UUID): null tokenBalance returns 0")
    void getBalance_nullBalance_returnsZero() {
        user.setTokenBalance(null);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        assertThat(service.getBalance(USER_ID)).isZero();
    }

    @Test
    @DisplayName("getBalance(UUID): unknown user returns 0")
    void getBalance_unknownUser_returnsZero() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());
        assertThat(service.getBalance(USER_ID)).isZero();
    }

    // ─── credit ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("credit: balance increases by amount, transaction recorded")
    void credit_increasesBalance() {
        int newBalance = service.credit(USER_ID, 50, TokenReasonCode.ADMIN_GRANT, null);

        assertThat(newBalance).isEqualTo(150);
        assertThat(user.getTokenBalance()).isEqualTo(150);
        then(txRepository).should().save(any());
        then(userRepository).should(atLeastOnce()).save(user);
    }

    @Test
    @DisplayName("credit: controllers paused by tokens are restored when balance becomes positive")
    void credit_restoresPausedControllers() {
        user.setTokenBalance(0);
        given(subscriptionRepository.findAllByUserIdAndPausedByTokensTrue(USER_ID))
            .willReturn(List.of());
        given(controllerRepository.findAllById(anyList())).willReturn(List.of());
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        service.credit(USER_ID, 10, TokenReasonCode.ADMIN_GRANT, null);

        then(subscriptionRepository).should().updatePausedByTokensForUser(USER_ID, false);
    }

    // ─── debit ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("debit: balance decreases by amount, transaction recorded")
    void debit_decreasesBalance() {
        int newBalance = service.debit(USER_ID, 30, TokenReasonCode.CONTROLLER_POLL, null);

        assertThat(newBalance).isEqualTo(70);
        assertThat(user.getTokenBalance()).isEqualTo(70);
        then(txRepository).should().save(any());
    }

    @Test
    @DisplayName("debit: throws InsufficientTokensException when balance < amount")
    void debit_insufficientBalance_throwsException() {
        assertThatThrownBy(() -> service.debit(USER_ID, 200, TokenReasonCode.CONTROLLER_POLL, null))
            .isInstanceOf(InsufficientTokensException.class)
            .satisfies(e -> {
                InsufficientTokensException ex = (InsufficientTokensException) e;
                assertThat(ex.getRequired()).isEqualTo(200);
                assertThat(ex.getAvailable()).isEqualTo(100);
            });

        then(txRepository).should(never()).save(any());
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("debit to zero: pauses all active controllers")
    void debit_toZero_pausesControllers() {
        user.setTokenBalance(10);
        given(controllerRepository.findAllByUserIdAndIsActiveTrue(USER_ID)).willReturn(List.of());

        service.debit(USER_ID, 10, TokenReasonCode.CONTROLLER_POLL, null);

        then(subscriptionRepository).should().updatePausedByTokensForUser(USER_ID, true);
    }

    @Test
    @DisplayName("debit: threshold alert fired when balance drops below user threshold")
    void debit_belowThreshold_firesAlert() {
        user.setTokenLowThreshold(50);
        user.setTokenAlertSent(false);

        service.debit(USER_ID, 60, TokenReasonCode.CONTROLLER_POLL, null);

        then(eventPublisher).should().publishEvent(any(com.valui.user.event.TokenThresholdEvent.class));
        assertThat(user.isTokenAlertSent()).isTrue();
    }

    @Test
    @DisplayName("debit: no duplicate alert if tokenAlertSent already true")
    void debit_noDoubleAlert_whenAlreadySent() {
        user.setTokenLowThreshold(50);
        user.setTokenAlertSent(true);

        service.debit(USER_ID, 10, TokenReasonCode.CONTROLLER_POLL, null);

        then(eventPublisher).should(never()).publishEvent(any(com.valui.user.event.TokenThresholdEvent.class));
    }

    // ─── tryDebit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tryDebit: returns true on success")
    void tryDebit_success_returnsTrue() {
        assertThat(service.tryDebit(USER_ID, 10, TokenReasonCode.CONTROLLER_POLL, null)).isTrue();
    }

    @Test
    @DisplayName("tryDebit: returns false on insufficient balance, no exception thrown")
    void tryDebit_insufficient_returnsFalse() {
        assertThat(service.tryDebit(USER_ID, 999, TokenReasonCode.CONTROLLER_POLL, null)).isFalse();
    }

    // ─── unknown user ────────────────────────────────────────────────────────

    @Test
    @DisplayName("debit: throws UserNotFoundException for unknown userId")
    void debit_unknownUser_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        given(em.find(eq(UserEntity.class), eq(unknown), eq(LockModeType.PESSIMISTIC_WRITE), anyMap()))
            .willReturn(null);

        assertThatThrownBy(() -> service.debit(unknown, 10, TokenReasonCode.CONTROLLER_POLL, null))
            .isInstanceOf(UserNotFoundException.class);
    }
}
