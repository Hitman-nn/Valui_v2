package com.valui.admin.auth;

import com.valui.admin.auth.dto.AuthRequest;
import com.valui.admin.auth.dto.AuthResponse;
import com.valui.admin.auth.jwt.JwtProperties;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.auth.redis.RefreshToken;
import com.valui.admin.auth.redis.RefreshTokenRepository;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.ValuiException;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.service.SubscriptionService;
import com.valui.user.service.UserService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // setUp stubs shared across tests; some tests don't use all
@DisplayName("AuthService — unit tests")
class AuthServiceTest {

    @Mock private AuthProperties authProperties;
    @Mock private JwtProperties jwtProperties;
    @Mock private JwtService jwtService;
    @Mock private UserService userService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks private AuthService authService;

    private static final String VALID_SECRET = "correct-bot-secret";
    private static final Long   TELEGRAM_ID  = 12345L;
    private static final UUID   USER_ID      = UUID.randomUUID();

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
            .id(USER_ID).telegramId(TELEGRAM_ID)
            .role(UserRole.USER).status(UserStatus.ACTIVE).build();

        given(authProperties.botSecret()).willReturn(VALID_SECRET);
        given(jwtProperties.accessTokenTtlSeconds()).willReturn(3600L);
    }

    // ─── authenticate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate: valid botSecret → issues tokens and persists refresh token")
    void authenticate_validSecret_returnsTokens() {
        given(userService.registerOrGetUser(any(TelegramUserDto.class))).willReturn(user);
        given(subscriptionService.getUserPlan(TELEGRAM_ID)).willReturn(planDto("PRO"));
        given(jwtService.generateAccessToken(USER_ID, TELEGRAM_ID, "USER", "PRO"))
            .willReturn("access.token.value");
        given(refreshTokenRepository.save(any(RefreshToken.class)))
            .willAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.authenticate(new AuthRequest(TELEGRAM_ID, VALID_SECRET));

        assertThat(response.accessToken()).isEqualTo("access.token.value");
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(3600L);

        ArgumentCaptor<RefreshToken> rtCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        then(refreshTokenRepository).should().save(rtCaptor.capture());
        RefreshToken saved = rtCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(saved.getTelegramId()).isEqualTo(TELEGRAM_ID);
        assertThat(saved.getRole()).isEqualTo("USER");
    }

    @Test
    @DisplayName("authenticate: wrong botSecret → ValuiException(401)")
    void authenticate_wrongSecret_throwsValuiException() {
        assertThatThrownBy(() ->
            authService.authenticate(new AuthRequest(TELEGRAM_ID, "wrong-secret")))
            .isInstanceOf(ValuiException.class)
            .satisfies(e -> assertThat(((ValuiException) e).getHttpStatus()).isEqualTo(401));

        then(userService).should(never()).registerOrGetUser(any());
        then(refreshTokenRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("authenticate: subscription lookup fails → defaults to FREE plan")
    void authenticate_noSubscription_defaultsToFree() {
        given(userService.registerOrGetUser(any())).willReturn(user);
        given(subscriptionService.getUserPlan(TELEGRAM_ID))
            .willThrow(new IllegalStateException("no sub"));
        given(jwtService.generateAccessToken(USER_ID, TELEGRAM_ID, "USER", "FREE"))
            .willReturn("access.token.free");
        given(refreshTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.authenticate(new AuthRequest(TELEGRAM_ID, VALID_SECRET));

        assertThat(response.accessToken()).isEqualTo("access.token.free");
        then(jwtService).should().generateAccessToken(USER_ID, TELEGRAM_ID, "USER", "FREE");
    }

    // ─── refresh ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh: valid refresh token → new access token (same refresh token)")
    void refresh_validToken_returnsNewAccessToken() {
        String tokenValue = UUID.randomUUID().toString();
        RefreshToken rt = new RefreshToken(tokenValue, USER_ID.toString(), TELEGRAM_ID, "USER");
        given(refreshTokenRepository.findById(tokenValue)).willReturn(Optional.of(rt));
        given(userService.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));
        given(subscriptionService.getUserPlan(TELEGRAM_ID)).willReturn(planDto("FREE"));
        given(jwtService.generateAccessToken(USER_ID, TELEGRAM_ID, "USER", "FREE"))
            .willReturn("new.access.token");

        AuthResponse response = authService.refresh(tokenValue);

        assertThat(response.accessToken()).isEqualTo("new.access.token");
        assertThat(response.refreshToken()).isEqualTo(tokenValue); // unchanged
        then(refreshTokenRepository).should(never()).save(any());  // token not rotated
    }

    @Test
    @DisplayName("refresh: unknown token → ValuiException(401)")
    void refresh_unknownToken_throwsValuiException() {
        given(refreshTokenRepository.findById(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("bogus-token"))
            .isInstanceOf(ValuiException.class)
            .satisfies(e -> assertThat(((ValuiException) e).getHttpStatus()).isEqualTo(401));
    }

    // ─── logout ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout: deletes refresh token from Redis")
    void logout_deletesRefreshToken() {
        String tokenValue = UUID.randomUUID().toString();

        authService.logout(tokenValue);

        then(refreshTokenRepository).should().deleteById(tokenValue);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static SubscriptionPlanDto planDto(String code) {
        return new SubscriptionPlanDto(UUID.randomUUID(), code, code + " plan",
            3, 5, 120, List.of("XBET"), List.of("TELEGRAM"), BigDecimal.ZERO);
    }
}
