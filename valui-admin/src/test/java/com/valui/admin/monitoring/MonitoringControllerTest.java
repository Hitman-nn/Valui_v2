package com.valui.admin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.admin.auth.AuthProperties;
import com.valui.admin.auth.jwt.JwtAuthenticationFilter;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.config.WebSecurityConfig;
import com.valui.admin.security.CurrentUserArgumentResolver;
import com.valui.admin.security.CurrentUserUtil;
import com.valui.admin.support.TestJwtConfig;
import com.valui.common.domain.ControllerType;
import com.valui.common.exception.ControllerAccessException;
import com.valui.common.exception.ControllerNotFoundException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MonitoringController.class)
@Import({
        WebSecurityConfig.class,
        JwtAuthenticationFilter.class,
        CurrentUserArgumentResolver.class,
        CurrentUserUtil.class,
        ControllerAssembler.class,
        TestJwtConfig.class
})
@DisplayName("MonitoringController — @WebMvcTest")
class MonitoringControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwtService;

    @MockBean ControllerService controllerService;
    @MockBean AuthProperties authProperties;

    static final UUID   USER_ID   = UUID.fromString("22222222-0000-0000-0000-000000000002");
    static final Long   TELEGRAM  = 987654321L;
    static final UUID   CTRL_ID   = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private String bearer;
    private ControllerDto controllerDto;

    @BeforeEach
    void setUp() {
        bearer = TestJwtConfig.bearer(jwtService, USER_ID, TELEGRAM, "USER");

        controllerDto = new ControllerDto(
                CTRL_ID, "XBET",
                "https://1xbet.kz/line/football/123",
                "Лига чемпионов", "Реал|Барса",
                false, true,
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(60),
                5, ControllerType.TOURNAMENT,
                TELEGRAM, TELEGRAM, 20,
                Instant.now().minusSeconds(7200));
    }

    // ── GET /api/v1/controllers ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /controllers → 200 с постраничным списком")
    void listControllers_authenticated_returns200() throws Exception {
        var page = new PageImpl<>(List.of(controllerDto), PageRequest.of(0, 20), 1);
        given(controllerService.getUserControllers(eq(TELEGRAM), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/controllers")
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.controllers[0].id").value(CTRL_ID.toString()))
                .andExpect(jsonPath("$._embedded.controllers[0].bookmaker").value("XBET"))
                .andExpect(jsonPath("$._embedded.controllers[0]._links.self.href").exists())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /controllers — без токена → 401")
    void listControllers_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/controllers"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/controllers ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /controllers — валидный запрос → 201 с Location")
    void createController_valid_returns201() throws Exception {
        given(controllerService.addController(any(CreateControllerRequest.class),
                eq(TELEGRAM), eq(TELEGRAM)))
                .willReturn(controllerDto);

        String body = """
                {
                  "url": "https://1xbet.kz/line/football/123",
                  "title": "Лига чемпионов",
                  "muted": false
                }
                """;

        mockMvc.perform(post("/api/v1/controllers")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString(CTRL_ID.toString())))
                .andExpect(jsonPath("$.id").value(CTRL_ID.toString()))
                .andExpect(jsonPath("$._links.self.href").exists())
                .andExpect(jsonPath("$._links.mute.href").exists());
    }

    @Test
    @DisplayName("POST /controllers — url без домена букмекера → 400")
    void createController_unknownBookmakerUrl_returns400() throws Exception {
        String body = """
                {"url": "https://unknown-site.ru/sports/football/123"}
                """;

        mockMvc.perform(post("/api/v1/controllers")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /controllers — url пустой → 400")
    void createController_blankUrl_returns400() throws Exception {
        String body = """
                {"url": ""}
                """;

        mockMvc.perform(post("/api/v1/controllers")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /controllers — без токена → 401")
    void createController_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/controllers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://1xbet.kz/line/1\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/controllers/{id} ──────────────────────────────────────────

    @Test
    @DisplayName("GET /controllers/{id} — существующий → 200")
    void getController_found_returns200() throws Exception {
        given(controllerService.getControllerForChat(CTRL_ID, TELEGRAM))
                .willReturn(controllerDto);

        mockMvc.perform(get("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CTRL_ID.toString()))
                .andExpect(jsonPath("$.title").value("Лига чемпионов"))
                .andExpect(jsonPath("$._links.delete.href").exists());
    }

    @Test
    @DisplayName("GET /controllers/{id} — не найден → 404")
    void getController_notFound_returns404() throws Exception {
        given(controllerService.getControllerForChat(CTRL_ID, TELEGRAM))
                .willThrow(new ControllerNotFoundException(CTRL_ID));

        mockMvc.perform(get("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /controllers/{id} — чужой контроллер → 403")
    void getController_notOwner_returns403() throws Exception {
        given(controllerService.getControllerForChat(CTRL_ID, TELEGRAM))
                .willThrow(new ControllerAccessException(CTRL_ID));

        mockMvc.perform(get("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /controllers/{id} — без токена → 401")
    void getController_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/controllers/{id}", CTRL_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /api/v1/controllers/{id} ───────────────────────────────────────

    @Test
    @DisplayName("PATCH /controllers/{id} — обновляет filterRule → 200")
    void updateController_filterRule_returns200() throws Exception {
        given(controllerService.getController(CTRL_ID)).willReturn(controllerDto);
        given(controllerService.updateFilterRule(CTRL_ID, TELEGRAM, "Реал"))
                .willReturn(controllerDto);

        String body = """
                {"filterRule": "Реал"}
                """;

        mockMvc.perform(patch("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /controllers/{id} — filterRule слишком длинный → 400")
    void updateController_filterRuleTooLong_returns400() throws Exception {
        String longRule = "a".repeat(501);
        String body = "{\"filterRule\": \"" + longRule + "\"}";

        mockMvc.perform(patch("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── DELETE /api/v1/controllers/{id} ──────────────────────────────────────

    @Test
    @DisplayName("DELETE /controllers/{id} → 204")
    void deleteController_existing_returns204() throws Exception {
        willDoNothing().given(controllerService).removeController(CTRL_ID, TELEGRAM);

        mockMvc.perform(delete("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /controllers/{id} — не найден → 404")
    void deleteController_notFound_returns404() throws Exception {
        willThrow(new ControllerNotFoundException(CTRL_ID))
                .given(controllerService).removeController(CTRL_ID, TELEGRAM);

        mockMvc.perform(delete("/api/v1/controllers/{id}", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /controllers/{id} — без токена → 401")
    void deleteController_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/controllers/{id}", CTRL_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/v1/controllers/{id}/mute ───────────────────────────────────

    @Test
    @DisplayName("POST /controllers/{id}/mute → 204")
    void muteController_returns204() throws Exception {
        willDoNothing().given(controllerService).muteController(CTRL_ID, TELEGRAM);

        mockMvc.perform(post("/api/v1/controllers/{id}/mute", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /controllers/{id}/mute — без токена → 401")
    void muteController_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/controllers/{id}/mute", CTRL_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/v1/controllers/{id}/mute ─────────────────────────────────

    @Test
    @DisplayName("DELETE /controllers/{id}/mute → 204")
    void unmuteController_returns204() throws Exception {
        willDoNothing().given(controllerService).unmuteController(CTRL_ID, TELEGRAM);

        mockMvc.perform(delete("/api/v1/controllers/{id}/mute", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /controllers/{id}/mute — не найден → 404")
    void unmuteController_notFound_returns404() throws Exception {
        willThrow(new ControllerNotFoundException(CTRL_ID))
                .given(controllerService).unmuteController(CTRL_ID, TELEGRAM);

        mockMvc.perform(delete("/api/v1/controllers/{id}/mute", CTRL_ID)
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }
}
