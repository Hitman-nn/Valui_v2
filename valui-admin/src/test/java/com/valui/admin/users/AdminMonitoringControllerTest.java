package com.valui.admin.users;

import com.valui.admin.auth.AuthProperties;
import com.valui.admin.auth.jwt.JwtAuthenticationFilter;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.config.WebSecurityConfig;
import com.valui.admin.monitoring.ControllerAssembler;
import com.valui.admin.security.CurrentUserArgumentResolver;
import com.valui.admin.security.CurrentUserUtil;
import com.valui.admin.support.TestJwtConfig;
import com.valui.common.domain.ControllerType;
import com.valui.common.exception.ControllerNotFoundException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.service.ControllerService;
import com.valui.user.repository.DetectedEventRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminMonitoringController.class)
@Import({
        WebSecurityConfig.class,
        JwtAuthenticationFilter.class,
        CurrentUserArgumentResolver.class,
        CurrentUserUtil.class,
        ControllerAssembler.class,
        TestJwtConfig.class
})
@DisplayName("AdminMonitoringController — @WebMvcTest")
class AdminMonitoringControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;

    @MockBean ControllerService controllerService;
    @MockBean AuthProperties authProperties;
    @MockBean DetectedEventRepository detectedEventRepository;
    @MockBean PollHistoryService pollHistoryService;

    static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-1111-0000-0000-000000000001");
    static final Long ADMIN_TG = 100000001L;
    static final UUID USER_ID  = UUID.fromString("bbbbbbbb-2222-0000-0000-000000000002");
    static final Long USER_TG  = 200000002L;
    static final UUID CTRL_ID  = UUID.fromString("cccccccc-3333-0000-0000-000000000003");

    private String adminBearer;
    private String userBearer;
    private ControllerDto controllerDto;

    @BeforeEach
    void setUp() {
        adminBearer = TestJwtConfig.bearer(jwtService, ADMIN_ID, ADMIN_TG, "ADMIN");
        userBearer  = TestJwtConfig.bearer(jwtService, USER_ID,  USER_TG,  "USER");

        controllerDto = new ControllerDto(
                CTRL_ID, "FONBET",
                "https://fonbet.ru/sports/football/123",
                "АПЛ", null,
                false, true,
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(60),
                12, ControllerType.TOURNAMENT,
                USER_TG, USER_TG, 20);
    }

    // ── GET /api/v1/admin/controllers ────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/controllers — ADMIN → 200 со всеми контроллерами")
    void listAllControllers_admin_returns200() throws Exception {
        var page = new PageImpl<>(List.of(controllerDto), PageRequest.of(0, 20), 1);
        given(controllerService.getAllControllers(any())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/controllers")
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.controllers[0].id").value(CTRL_ID.toString()))
                .andExpect(jsonPath("$._embedded.controllers[0].bookmaker").value("FONBET"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /admin/controllers — USER → 403")
    void listAllControllers_user_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/controllers")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/controllers — без токена → 401")
    void listAllControllers_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/controllers"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/admin/controllers/{id} ───────────────────────────────────

    @Test
    @DisplayName("GET /admin/controllers/{id} — ADMIN → 200 без проверки владельца")
    void getController_admin_returns200() throws Exception {
        given(controllerService.getController(CTRL_ID)).willReturn(controllerDto);

        mockMvc.perform(get("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CTRL_ID.toString()))
                .andExpect(jsonPath("$.ownerTelegramId").value(USER_TG));
    }

    @Test
    @DisplayName("GET /admin/controllers/{id} — не найден → 404")
    void getController_notFound_returns404() throws Exception {
        given(controllerService.getController(CTRL_ID))
                .willThrow(new ControllerNotFoundException(CTRL_ID));

        mockMvc.perform(get("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", adminBearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /admin/controllers/{id} — USER → 403")
    void getController_user_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/controllers/{id} — без токена → 401")
    void getController_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/controllers/{id}", CTRL_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/v1/admin/controllers/{id} ────────────────────────────────

    @Test
    @DisplayName("DELETE /admin/controllers/{id} — ADMIN → 204")
    void deactivateController_admin_returns204() throws Exception {
        willDoNothing().given(controllerService).deactivateController(CTRL_ID);

        mockMvc.perform(delete("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", adminBearer))
                .andExpect(status().isNoContent());

        verify(controllerService).deactivateController(CTRL_ID);
    }

    @Test
    @DisplayName("DELETE /admin/controllers/{id} — не найден → 404")
    void deactivateController_notFound_returns404() throws Exception {
        willThrow(new ControllerNotFoundException(CTRL_ID))
                .given(controllerService).deactivateController(CTRL_ID);

        mockMvc.perform(delete("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /admin/controllers/{id} — USER → 403 (деактивация не происходит)")
    void deactivateController_user_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/controllers/{id}", CTRL_ID)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());

        verify(controllerService, never()).deactivateController(any());
    }

    @Test
    @DisplayName("DELETE /admin/controllers/{id} — без токена → 401")
    void deactivateController_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/controllers/{id}", CTRL_ID))
                .andExpect(status().isUnauthorized());
    }
}
