package com.valui.admin.auth.jwt;

import com.valui.admin.security.ValuiPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtService — unit tests")
class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder()
        .encodeToString("test-jwt-secret-key-must-be-32-bytes!!".getBytes(StandardCharsets.UTF_8));

    private JwtService jwtService;

    private final UUID   userId     = UUID.randomUUID();
    private final Long   telegramId = 99L;
    private final String role       = "USER";
    private final String plan       = "PRO";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new JwtProperties(SECRET, 3600L, 2_592_000L));
    }

    // ─── generation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken: token contains correct claims")
    void generateAccessToken_containsCorrectClaims() {
        String token = jwtService.generateAccessToken(userId, telegramId, role, plan);

        assertThat(token).isNotBlank();
        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("telegramId", Long.class)).isEqualTo(telegramId);
        assertThat(claims.get("role", String.class)).isEqualTo(role);
        assertThat(claims.get("plan", String.class)).isEqualTo(plan);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    // ─── extractPrincipal ────────────────────────────────────────────────────

    @Test
    @DisplayName("extractPrincipal: round-trip produces identical principal")
    void extractPrincipal_roundTrip_returnsCorrectPrincipal() {
        String token = jwtService.generateAccessToken(userId, telegramId, role, plan);

        ValuiPrincipal principal = jwtService.extractPrincipal(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.telegramId()).isEqualTo(telegramId);
        assertThat(principal.role()).isEqualTo(role);
        assertThat(principal.plan()).isEqualTo(plan);
    }

    // ─── validation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("isTokenValid: valid token returns true")
    void isTokenValid_validToken_returnsTrue() {
        String token = jwtService.generateAccessToken(userId, telegramId, role, plan);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("isTokenValid: expired token returns false")
    void isTokenValid_expiredToken_returnsFalse() {
        // Use a service with TTL = -1 s (token already expired on creation)
        JwtService expiredService = new JwtService(new JwtProperties(SECRET, -1L, 2_592_000L));
        String expiredToken = expiredService.generateAccessToken(userId, telegramId, role, plan);

        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid: tampered token returns false")
    void isTokenValid_tamperedToken_returnsFalse() {
        String token = jwtService.generateAccessToken(userId, telegramId, role, plan);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("parseClaims: malformed token throws JwtException")
    void parseClaims_malformedToken_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.parseClaims("not.a.jwt"))
            .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("parseClaims: token signed with different key throws JwtException")
    void parseClaims_differentKey_throwsJwtException() {
        String otherSecret = Base64.getEncoder()
            .encodeToString("other-secret-key-that-is-32-bytes!!".getBytes(StandardCharsets.UTF_8));
        JwtService otherService = new JwtService(new JwtProperties(otherSecret, 3600L, 2_592_000L));
        String foreignToken = otherService.generateAccessToken(userId, telegramId, role, plan);

        assertThatThrownBy(() -> jwtService.parseClaims(foreignToken))
            .isInstanceOf(JwtException.class);
    }
}
