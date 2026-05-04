package com.valui.admin.support;

import com.valui.admin.auth.jwt.JwtProperties;
import com.valui.admin.auth.jwt.JwtService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

/**
 * Provides a real {@link JwtService} backed by a deterministic test secret.
 * Import this config in {@code @WebMvcTest} slices that need valid Bearer tokens.
 *
 * Usage:
 * <pre>
 *   String token = jwtConfig.token(userId, telegramId, "USER");
 *   mockMvc.perform(get("...").header("Authorization", "Bearer " + token));
 * </pre>
 */
@TestConfiguration
public class TestJwtConfig {

    // 48-byte key (384 bits) — satisfies HS256 minimum requirement
    public static final String TEST_SECRET =
            "dmFsdWktdjItdGVzdC1zZWNyZXQtZm9yLWp3dC10b2tlbi12YWxpZGF0aW9u";

    @Bean
    @Primary
    public JwtProperties testJwtProperties() {
        return new JwtProperties(TEST_SECRET, 3600L, 2592000L);
    }

    @Bean
    @Primary
    public JwtService testJwtService(JwtProperties props) {
        return new JwtService(props);
    }

    // ── Helpers used directly in tests ────────────────────────────────────────

    public static String token(JwtService jwt, UUID userId, Long telegramId, String role) {
        return jwt.generateAccessToken(userId, telegramId, role, "FREE");
    }

    public static String bearer(JwtService jwt, UUID userId, Long telegramId, String role) {
        return "Bearer " + token(jwt, userId, telegramId, role);
    }
}
