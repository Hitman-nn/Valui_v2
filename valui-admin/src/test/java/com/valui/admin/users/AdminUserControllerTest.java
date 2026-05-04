package com.valui.admin.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.admin.auth.AuthProperties;
import com.valui.admin.auth.jwt.JwtAuthenticationFilter;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.config.WebSecurityConfig;
import com.valui.admin.security.CurrentUserArgumentResolver;
import com.valui.admin.security.CurrentUserUtil;
import com.valui.admin.support.TestJwtConfig;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.UserWithSubscriptionDto;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.NotificationLogRepository;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import({
        WebSecurityConfig.class,
        JwtAuthenticationFilter.class,
        CurrentUserArgumentResolver.class,
        CurrentUserUtil.class,
        AdminUserAssembler.class,
        TestJwtConfig.class
})
@DisplayName("AdminUserController — @WebMvcTest")
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwtService;

    @MockBean UserService userService;
    @MockBean AuthProperties authProperties;
    @MockBean AuditLogRepository auditLogRepository;
    @MockBean NotificationLogRepository notificationLogRepository;

    static final UUID ADMIN_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final Long ADMIN_TG   = 111111111L;
    static final UUID USER_ID    = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final Long USER_TG    = 222222222L;

    private String adminBearer;
    private String userBearer;
    private UserEntity targetUser;

    @BeforeEach
    void setUp() {
        adminBearer = TestJwtConfig.bearer(jwtService, ADMIN_ID, ADMIN_TG, "ADMIN");
        userBearer  = TestJwtConfig.bearer(jwtService, USER_ID,  USER_TG,  "USER");

        targetUser = UserEntity.builder()
                .id(USER_ID)
                .telegramId(USER_TG)
                .username("john_doe")
                .firstName("John")
                .languageCode("ru")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenBalance(100)
                .tokenLowThresholdPct(20)
                .tokenMonthlyGrantRef(200)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // ── GET /api/v1/admin/users ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/users — ADMIN → 200 с пагинацией")
    void listUsers_admin_returns200() throws Exception {
        var page = new PageImpl<>(List.of(targetUser), PageRequest.of(0, 20), 1);
        given(userService.findAllUsers(any())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.users[0].id").value(USER_ID.toString()))
                .andExpect(jsonPath("$._embedded.users[0].username").value("john_doe"))
                .andExpect(jsonPath("$._embedded.users[0]._links.self.href").exists())
                .andExpect(jsonPath("$._embedded.users[0]._links.ban.href").exists())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /admin/users — USER → 403")
    void listUsers_user_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/users — без токена → 401")
    void listUsers_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/admin/users/{id} ──────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/users/{id} — ADMIN → 200 с полными данными и _links")
    void getUser_admin_returns200() throws Exception {
        given(userService.findById(USER_ID)).willReturn(targetUser);
        given(userService.getUserWithSubscription(USER_TG))
                .willReturn(new UserWithSubscriptionDto(targetUser, null, null));

        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID)
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tokenBalance").value(100))
                .andExpect(jsonPath("$._links.self.href").exists())
                .andExpect(jsonPath("$._links.ban.href").exists())
                .andExpect(jsonPath("$._links.changeRole.href").exists());
    }

    @Test
    @DisplayName("GET /admin/users/{id} — пользователь не найден → 404")
    void getUser_notFound_returns404() throws Exception {
        given(userService.findById(USER_ID))
                .willThrow(new UserNotFoundException(USER_ID));

        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID)
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /admin/users/{id} — USER → 403")
    void getUser_user_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/users/{id} — без токена → 401")
    void getUser_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/admin/users/{id}/ban ────────────────────────────────────

    @Test
    @DisplayName("POST /admin/users/{id}/ban — ADMIN → 204")
    void banUser_admin_returns204() throws Exception {
        willDoNothing().given(userService).banUser(USER_ID);

        mockMvc.perform(post("/api/v1/admin/users/{id}/ban", USER_ID)
                        .header("Authorization", adminBearer))
                .andExpect(status().isNoContent());

        verify(userService).banUser(USER_ID);
    }

    @Test
    @DisplayName("POST /admin/users/{id}/ban — USER → 403 (Kafka-событие не публикуется)")
    void banUser_user_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/ban", USER_ID)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());

        verify(userService, never()).banUser(any());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/ban — пользователь не найден → 404")
    void banUser_notFound_returns404() throws Exception {
        willThrow(new UserNotFoundException(USER_ID))
                .given(userService).banUser(USER_ID);

        mockMvc.perform(post("/api/v1/admin/users/{id}/ban", USER_ID)
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /admin/users/{id}/ban — без токена → 401")
    void banUser_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/ban", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/v1/admin/users/{id}/ban ──────────────────────────────────

    @Test
    @DisplayName("DELETE /admin/users/{id}/ban — ADMIN → 204")
    void unbanUser_admin_returns204() throws Exception {
        willDoNothing().given(userService).unbanUser(USER_ID);

        mockMvc.perform(delete("/api/v1/admin/users/{id}/ban", USER_ID)
                        .header("Authorization", adminBearer))
                .andExpect(status().isNoContent());

        verify(userService).unbanUser(USER_ID);
    }

    @Test
    @DisplayName("DELETE /admin/users/{id}/ban — USER → 403")
    void unbanUser_user_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{id}/ban", USER_ID)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /admin/users/{id}/ban — без токена → 401")
    void unbanUser_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{id}/ban", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /api/v1/admin/users/{id}/role ──────────────────────────────────

    @Test
    @DisplayName("PATCH /admin/users/{id}/role — ADMIN → 204")
    void changeRole_admin_returns204() throws Exception {
        willDoNothing().given(userService).updateRole(eq(USER_ID), eq(UserRole.ADMIN));

        String body = """
                {"role": "ADMIN"}
                """;

        mockMvc.perform(patch("/api/v1/admin/users/{id}/role", USER_ID)
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(userService).updateRole(USER_ID, UserRole.ADMIN);
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/role — невалидная роль → 400")
    void changeRole_invalidRole_returns400() throws Exception {
        String body = """
                {"role": null}
                """;

        mockMvc.perform(patch("/api/v1/admin/users/{id}/role", USER_ID)
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/role — USER → 403")
    void changeRole_user_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/{id}/role", USER_ID)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/role — без токена → 401")
    void changeRole_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/{id}/role", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── HATEOAS: _links.unban для забаненного пользователя ───────────────────

    @Test
    @DisplayName("GET /admin/users/{id} — забаненный пользователь → _links.unban вместо ban")
    void getUser_banned_hasUnbanLink() throws Exception {
        targetUser.setStatus(UserStatus.BANNED);
        given(userService.findById(USER_ID)).willReturn(targetUser);
        given(userService.getUserWithSubscription(USER_TG))
                .willThrow(new RuntimeException("no sub"));

        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID)
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.unban.href").exists())
                .andExpect(jsonPath("$._links.ban").doesNotExist());
    }
}
