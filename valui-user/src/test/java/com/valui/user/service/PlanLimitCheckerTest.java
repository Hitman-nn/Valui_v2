package com.valui.user.service;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.of;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanLimitChecker — unit + parameterized tests")
class PlanLimitCheckerTest {

    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private ControllerRepository controllerRepository;
    @Mock private GlobalFilterRepository globalFilterRepository;
    @Mock private GroupQuotaService groupQuotaService;

    @InjectMocks private PlanLimitChecker checker;

    private static final Long TG_ID   = 77L;
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
            .id(USER_ID).telegramId(TG_ID)
            .role(UserRole.USER).status(UserStatus.ACTIVE)
            .tokenBalance(5).build();
    }

    // ─── checkControllerLimit ─────────────────────────────────────────────────

    @ParameterizedTest(name = "plan={0} max={1} used={2} shouldThrow={3}")
    @MethodSource("controllerLimitCases")
    @DisplayName("checkControllerLimit: parameterized per plan")
    void checkControllerLimit_parameterized(String planCode, int maxC, int used, boolean shouldThrow) {
        given(subscriptionService.canAddController(TG_ID)).willReturn(used < maxC);
        if (shouldThrow) {
            given(subscriptionService.getUserPlan(TG_ID)).willReturn(planDto(planCode, maxC, 5));
        }

        if (shouldThrow) {
            assertThatThrownBy(() -> checker.checkControllerLimit(TG_ID))
                .isInstanceOf(SubscriptionLimitExceededException.class)
                .hasMessageContaining("controllers");
        } else {
            assertThatNoException().isThrownBy(() -> checker.checkControllerLimit(TG_ID));
        }
    }

    static Stream<Arguments> controllerLimitCases() {
        return Stream.of(
            of("FREE",    3,   2, false),
            of("FREE",    3,   3, true),
            of("PRO",    15,  14, false),
            of("PRO",    15,  15, true),
            of("PREMIUM",100, 99, false),
            of("PREMIUM",100,100, true)
        );
    }

    // ─── checkBookmakerAccess ─────────────────────────────────────────────────

    @ParameterizedTest(name = "plan={0} bookmaker={1} allowed={2}")
    @MethodSource("bookmakerAccessCases")
    @DisplayName("checkBookmakerAccess: parameterized per plan + bookmaker")
    void checkBookmakerAccess_parameterized(String planCode, String bookmaker,
                                            boolean allowed, String[] planBookmakers) {
        given(subscriptionService.canUseBookmaker(TG_ID, bookmaker)).willReturn(allowed);
        if (!allowed) {
            given(subscriptionService.getUserPlan(TG_ID))
                .willReturn(planDto(planCode, 3, 5, planBookmakers));
        }

        if (!allowed) {
            assertThatThrownBy(() -> checker.checkBookmakerAccess(TG_ID, bookmaker))
                .isInstanceOf(SubscriptionLimitExceededException.class)
                .hasMessageContaining(bookmaker);
        } else {
            assertThatNoException().isThrownBy(() -> checker.checkBookmakerAccess(TG_ID, bookmaker));
        }
    }

    static Stream<Arguments> bookmakerAccessCases() {
        String[] freeBookmakers = {"XBET", "FONBET"};
        String[] allBookmakers  = {"XBET", "FONBET", "OLIMP", "BETCITY", "BETBOOM"};
        return Stream.of(
            of("FREE",    "XBET",    true,  freeBookmakers),
            of("FREE",    "OLIMP",   false, freeBookmakers),
            of("PRO",     "BETBOOM", true,  allBookmakers),
            of("PREMIUM", "BETCITY", true,  allBookmakers),
            of("FREE",    "BETBOOM", false, freeBookmakers)
        );
    }

    // ─── checkFilterLimit ────────────────────────────────────────────────────

    @ParameterizedTest(name = "plan={0} maxFilters={1} filtersUsed={2} shouldThrow={3}")
    @MethodSource("filterLimitCases")
    @DisplayName("checkFilterLimit: parameterized per plan")
    void checkFilterLimit_parameterized(String planCode, int maxF, long filtersUsed, boolean shouldThrow) {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionService.getUserPlan(TG_ID)).willReturn(planDto(planCode, 3, maxF));
        given(controllerRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(1);
        given(globalFilterRepository.countByUserId(USER_ID)).willReturn(filtersUsed);
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.empty());

        if (shouldThrow) {
            assertThatThrownBy(() -> checker.checkFilterLimit(TG_ID))
                .isInstanceOf(SubscriptionLimitExceededException.class)
                .hasMessageContaining("filters");
        } else {
            assertThatNoException().isThrownBy(() -> checker.checkFilterLimit(TG_ID));
        }
    }

    static Stream<Arguments> filterLimitCases() {
        return Stream.of(
            of("FREE",    5,  4, false),
            of("FREE",    5,  5, true),
            of("PRO",    30, 29, false),
            of("PRO",    30, 30, true),
            of("PREMIUM",200, 0, false),
            of("PREMIUM",200,200,true)
        );
    }

    // ─── getLimitInfo ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getLimitInfo: assembles all fields correctly")
    void getLimitInfo_returnsCorrectSnapshot() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionService.getUserPlan(TG_ID)).willReturn(planDto("PRO", 15, 30));
        given(controllerRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(5);
        given(globalFilterRepository.countByUserId(USER_ID)).willReturn(2L);
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.empty());

        LimitInfoDto info = checker.getLimitInfo(TG_ID);

        assertThat(info.controllersUsed()).isEqualTo(5);
        assertThat(info.controllersMax()).isEqualTo(15);
        assertThat(info.filtersUsed()).isEqualTo(2);
        assertThat(info.filtersMax()).isEqualTo(30);
        assertThat(info.planName()).isEqualTo("PRO plan");
        assertThat(info.expiresAt()).isNull();
        assertThat(info.tokenBalance()).isEqualTo(5); // from user.getTokenBalance()
    }

    @Test
    @DisplayName("getLimitInfo: user not found → UserNotFoundException")
    void getLimitInfo_userNotFound_throws() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.empty());
        // No subscription stub needed — exception thrown before that lookup
        assertThatThrownBy(() -> checker.getLimitInfo(TG_ID))
            .isInstanceOf(UserNotFoundException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static SubscriptionPlanDto planDto(String code, int maxC, int maxF, String... bookmakers) {
        List<String> bk = bookmakers.length > 0
            ? List.of(bookmakers)
            : List.of("XBET", "FONBET");
        return new SubscriptionPlanDto(
            UUID.randomUUID(), code, code + " plan",
            maxC, maxF, 60,
            bk, List.of("TELEGRAM"),
            BigDecimal.ONE, 0
        );
    }
}
