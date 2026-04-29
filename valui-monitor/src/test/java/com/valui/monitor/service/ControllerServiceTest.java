package com.valui.monitor.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.ControllerAccessException;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.ValuiException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ControllerService — unit tests")
class ControllerServiceTest {

    @Mock ControllerPortService controllerPort;
    @Mock DetectedEventPortService detectedEventPort;
    @Mock UserService userService;
    @Mock PlanLimitFacade planLimitFacade;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks ControllerServiceImpl service;

    static final Long TG_ID     = 100L;
    static final UUID USER_ID   = UUID.randomUUID();
    static final String XBET_URL = "https://1xstavka.ru/line/football/12345";

    UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
                .id(USER_ID).telegramId(TG_ID)
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
    }

    // ── addController ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("addController: happy path — saves entity and returns ControllerDto")
    void addController_happyPath_returnsDto() {
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(planLimitFacade.getLimitInfo(TG_ID)).willReturn(limitInfo(5, 120));
        given(controllerPort.existsByUserAndBookmakerAndUrl(any(), any(), any())).willReturn(false);
        given(detectedEventPort.countByControllerId(any())).willReturn(0L);
        given(controllerPort.save(any())).willAnswer(inv -> {
            ControllerEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        ControllerDto dto = service.addController(
                new CreateControllerRequest(XBET_URL, null, "Premier League", false), TG_ID);

        assertThat(dto.bookmaker()).isEqualTo("XBET");
        assertThat(dto.url()).isEqualTo(XBET_URL);
        assertThat(dto.title()).isEqualTo("Premier League");
        assertThat(dto.isActive()).isTrue();
        assertThat(dto.detectedEventsCount()).isZero();
        verify(planLimitFacade).checkControllerLimit(TG_ID);
        verify(planLimitFacade).checkBookmakerAccess(TG_ID, "XBET");
        verify(controllerPort).save(any(ControllerEntity.class));
    }

    @Test
    @DisplayName("addController: explicit bookmaker in request overrides URL detection")
    void addController_explicitBookmaker_usesIt() {
        String olimpUrl = "https://olimp.bet/line/football/999";
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(planLimitFacade.getLimitInfo(TG_ID)).willReturn(limitInfo(5, 60));
        given(controllerPort.existsByUserAndBookmakerAndUrl(any(), any(), any())).willReturn(false);
        given(detectedEventPort.countByControllerId(any())).willReturn(0L);
        given(controllerPort.save(any())).willAnswer(inv -> {
            ControllerEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID()); return e;
        });

        ControllerDto dto = service.addController(
                new CreateControllerRequest(olimpUrl, "OLIMP", null, false), TG_ID);

        assertThat(dto.bookmaker()).isEqualTo("OLIMP");
    }

    @Test
    @DisplayName("addController: controller limit exceeded — throws and never saves")
    void addController_limitExceeded_throwsAndNeverSaves() {
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        willThrow(new SubscriptionLimitExceededException("controllers", 3))
                .given(planLimitFacade).checkControllerLimit(TG_ID);

        assertThatThrownBy(() ->
                service.addController(new CreateControllerRequest(XBET_URL, null, null, false), TG_ID))
                .isInstanceOf(SubscriptionLimitExceededException.class);
        verify(controllerPort, never()).save(any());
    }

    @Test
    @DisplayName("addController: duplicate URL — throws 409 and never saves")
    void addController_duplicateUrl_throws409() {
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.existsByUserAndBookmakerAndUrl(
                eq(USER_ID), eq(BookmakerType.XBET), eq(XBET_URL))).willReturn(true);

        assertThatThrownBy(() ->
                service.addController(new CreateControllerRequest(XBET_URL, null, null, false), TG_ID))
                .isInstanceOf(ValuiException.class)
                .extracting("httpStatus").isEqualTo(409);
        verify(controllerPort, never()).save(any());
    }

    @Test
    @DisplayName("addController: unknown bookmaker URL — throws 400")
    void addController_unknownBookmaker_throws400() {
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() ->
                service.addController(new CreateControllerRequest("https://unknown-bk.com/line/1", null, null, false), TG_ID))
                .isInstanceOf(ValuiException.class)
                .extracting("httpStatus").isEqualTo(400);
    }

    // ── removeController ──────────────────────────────────────────────────────

    @Test
    @DisplayName("removeController: success — sets isActive=false")
    void removeController_success_deactivates() {
        UUID cid = UUID.randomUUID();
        ControllerEntity entity = controllerEntity(cid, XBET_URL, true);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.of(entity));

        service.removeController(cid, TG_ID);

        ArgumentCaptor<ControllerEntity> captor = ArgumentCaptor.forClass(ControllerEntity.class);
        verify(controllerPort).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }

    @Test
    @DisplayName("removeController: wrong owner — throws ControllerAccessException")
    void removeController_wrongOwner_throws() {
        UUID cid = UUID.randomUUID();
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeController(cid, TG_ID))
                .isInstanceOf(ControllerAccessException.class);
        verify(controllerPort, never()).save(any());
    }

    // ── muteController / unmuteController ─────────────────────────────────────

    @Test
    @DisplayName("muteController: sets isMuted=true")
    void muteController_setsFlag() {
        UUID cid = UUID.randomUUID();
        ControllerEntity entity = controllerEntity(cid, XBET_URL, true);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.of(entity));
        given(controllerPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.muteController(cid, TG_ID);

        assertThat(entity.getIsMuted()).isTrue();
    }

    @Test
    @DisplayName("unmuteController: sets isMuted=false")
    void unmuteController_clearsFlag() {
        UUID cid = UUID.randomUUID();
        ControllerEntity entity = controllerEntity(cid, XBET_URL, true);
        entity.setIsMuted(true);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.of(entity));
        given(controllerPort.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.unmuteController(cid, TG_ID);

        assertThat(entity.getIsMuted()).isFalse();
    }

    // ── updateFilterRule ──────────────────────────────────────────────────────

    @Test
    @DisplayName("updateFilterRule: new filter — checks limit then saves")
    void updateFilterRule_newFilter_checksLimit() {
        UUID cid = UUID.randomUUID();
        ControllerEntity entity = controllerEntity(cid, XBET_URL, true); // filterRule = null
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.of(entity));
        given(controllerPort.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(detectedEventPort.countByControllerId(cid)).willReturn(0L);

        service.updateFilterRule(cid, TG_ID, ".*Liverpool.*");

        verify(planLimitFacade).checkFilterLimit(TG_ID);
        assertThat(entity.getFilterRule()).isEqualTo(".*Liverpool.*");
    }

    @Test
    @DisplayName("updateFilterRule: updating existing filter — skips limit check")
    void updateFilterRule_existingFilter_skipsLimitCheck() {
        UUID cid = UUID.randomUUID();
        ControllerEntity entity = controllerEntity(cid, XBET_URL, true);
        entity.setFilterRule(".*old.*");
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findByIdAndUserId(cid, USER_ID)).willReturn(Optional.of(entity));
        given(controllerPort.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(detectedEventPort.countByControllerId(cid)).willReturn(0L);

        service.updateFilterRule(cid, TG_ID, ".*new.*");

        verify(planLimitFacade, never()).checkFilterLimit(TG_ID);
    }

    // ── getUserControllers ────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserControllers(list): returns all active for user")
    void getUserControllers_returnsList() {
        ControllerEntity c1 = controllerEntity(UUID.randomUUID(), XBET_URL, true);
        ControllerEntity c2 = controllerEntity(UUID.randomUUID(), "https://olimp.bet/line/1", true);
        c2.setBookmaker(BookmakerType.OLIMP);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.findAllActiveByUserId(USER_ID)).willReturn(List.of(c1, c2));
        given(detectedEventPort.countByControllerId(any())).willReturn(0L);

        List<ControllerDto> result = service.getUserControllers(TG_ID);

        assertThat(result).hasSize(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ControllerEntity controllerEntity(UUID id, String url, boolean active) {
        ControllerEntity e = ControllerEntity.builder()
                .id(id).user(user)
                .bookmaker(BookmakerType.XBET)
                .url(url)
                .type(ControllerType.TOURNAMENT)
                .isActive(active).isMuted(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return e;
    }

    private static LimitInfoDto limitInfo(int maxControllers, int pollIntervalSec) {
        return new LimitInfoDto(0, maxControllers, 0, 5,
                List.of("XBET", "FONBET", "OLIMP", "BETCITY", "BETBOOM"),
                pollIntervalSec, "PRO", null, 0);
    }
}
