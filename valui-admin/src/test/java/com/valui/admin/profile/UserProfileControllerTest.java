package com.valui.admin.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.admin.auth.AuthProperties;
import com.valui.admin.auth.jwt.JwtAuthenticationFilter;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.config.WebSecurityConfig;
import com.valui.admin.profile.dto.SubscriptionInfoDto;
import com.valui.admin.security.CurrentUserArgumentResolver;
import com.valui.admin.security.CurrentUserUtil;
import com.valui.admin.support.TestJwtConfig;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.dto.UserWithSubscriptionDto;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserProfileController.class)
@Import({
        WebSecurityConfig.class,
        JwtAuthenticationFilter.class,
        CurrentUserArgumentResolver.class,
        CurrentUserUtil.class,
        UserProfileAssembler.class,
        TestJwtConfig.class
})
@DisplayName("UserProfileController — @WebMvcTest")
class UserProfileControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwtService;

    @MockBean UserService userService;
    @MockBean AuthProperties authProperties;   // needed by WebSecurityConfig

    static final UUID   USER_ID    = UUID.fromString("11111111-0000-0000-0000-000000000001");
    static final Long   TELEGRAM   = 123456789L;
    static final String ROLE_USER  = "USER";

    private String bearer;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        bearer = TestJwtConfig.bearer(jwtService, USER_ID, TELEGRAM, ROLE_USER);

        user = UserEntity.builder()
                .id(USER_ID)
                .telegramId(TELEGRAM)
                .username("john_doe")
                .firstName("John")
                .languageCode("ru")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenBalance(150)
                .tokenLowThresholdPct(20)
                .tokenMonthlyGrantRef(200)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // ── GET /api/v1/profile ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /profile → 200 с данными пользователя")
    void getProfile_authenticated_returns200() throws Exception {
        given(userService.findByTelegramId(TELEGRAM)).willReturn(Optional.of(user));

        mockMvc.perform(get("/api/v1/profile")
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramId").value(TELEGRAM))
                .andExpect(jsonPath("$.username").value("john_doe"))
                .andExpect(jsonPath("$.tokenBalance").value(150))
                .andExpect(jsonPath("$._links.self.href").exists())
                .andExpect(jsonPath("$._links.subscription.href").exists())
                .andExpect(jsonPath("$._links.tokens.href").exists());
    }

    @Test
    @DisplayName("GET /profile — нет токена → 401")
    void getProfile_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/profile").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /profile — пользователь не найден в БД → 404")
    void getProfile_userNotFound_returns404() throws Exception {
        given(userService.findByTelegramId(TELEGRAM)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/profile")
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /profile — поддерживает application/vnd.valui.v1+json")
    void getProfile_vendorMediaType_returns200() throws Exception {
        given(userService.findByTelegramId(TELEGRAM)).willReturn(Optional.of(user));

        mockMvc.perform(get("/api/v1/profile")
                        .header("Authorization", bearer)
                        .accept(UserProfileController.V1))
                .andExpect(status().isOk());
    }

    // ── PATCH /api/v1/profile ─────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /profile — обновляет username и languageCode → 200")
    void updateProfile_validRequest_returns200() throws Exception {
        given(userService.findByTelegramId(TELEGRAM)).willReturn(Optional.of(user));
        given(userService.updateUsername(TELEGRAM, "new_name")).willReturn(user);
        willDoNothing().given(userService).updateLanguage(anyLong(), anyString());

        String body = """
                {"username": "new_name", "languageCode": "en"}
                """;

        mockMvc.perform(patch("/api/v1/profile")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramId").value(TELEGRAM));

        verify(userService).updateUsername(TELEGRAM, "new_name");
        verify(userService).updateLanguage(TELEGRAM, "en");
    }

    @Test
    @DisplayName("PATCH /profile — невалидный username (спецсимволы) → 400")
    void updateProfile_invalidUsername_returns400() throws Exception {
        String body = """
                {"username": "bad name!@#"}
                """;

        mockMvc.perform(patch("/api/v1/profile")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /profile — невалидный languageCode → 400")
    void updateProfile_invalidLanguageCode_returns400() throws Exception {
        String body = """
                {"languageCode": "russian"}
                """;

        mockMvc.perform(patch("/api/v1/profile")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /profile — без токена → 401")
    void updateProfile_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/profile/subscription ─────────────────────────────────────

    @Test
    @DisplayName("GET /profile/subscription → 200 с данными подписки")
    void getSubscription_active_returns200() throws Exception {
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        plan.setCode("PRO");
        plan.setName("Pro");
        plan.setPriceRub(BigDecimal.valueOf(299));
        plan.setMaxControllers(10);
        plan.setPollIntervalSec(60);
        plan.setMonthlyTokenGrant(200);

        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setId(UUID.randomUUID());
        sub.setStartedAt(OffsetDateTime.now().minusDays(5));
        sub.setExpiresAt(OffsetDateTime.now().plusDays(25));

        given(userService.getUserWithSubscription(TELEGRAM))
                .willReturn(new UserWithSubscriptionDto(user, plan, sub));

        mockMvc.perform(get("/api/v1/profile/subscription")
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PRO"))
                .andExpect(jsonPath("$.maxControllers").value(10))
                .andExpect(jsonPath("$.monthlyTokenGrant").value(200));
    }

    // ── GET /api/v1/profile/tokens ────────────────────────────────────────────

    @Test
    @DisplayName("GET /profile/tokens → 200 с балансом токенов")
    void getTokens_returns200() throws Exception {
        given(userService.findByTelegramId(TELEGRAM)).willReturn(Optional.of(user));

        mockMvc.perform(get("/api/v1/profile/tokens")
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150))
                .andExpect(jsonPath("$.monthlyGrant").value(200))
                .andExpect(jsonPath("$.lowThresholdPct").value(20));
    }

    @Test
    @DisplayName("GET /profile/tokens — без токена → 401")
    void getTokens_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/profile/tokens"))
                .andExpect(status().isUnauthorized());
    }
}
