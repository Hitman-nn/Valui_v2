package com.valui.user.service;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.event.SubscriptionExpiredEvent;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.SubscriptionPlanRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionService — unit tests")
class SubscriptionServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock private ControllerRepository controllerRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private SubscriptionServiceImpl subscriptionService;

    private static final Long TG_ID   = 42L;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SUB_ID  = UUID.randomUUID();

    private UserEntity user;
    private SubscriptionPlanEntity freePlan;
    private SubscriptionPlanEntity proPlan;
    private SubscriptionEntity activeSub;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
            .id(USER_ID).telegramId(TG_ID).username("u")
            .role(UserRole.USER).status(UserStatus.ACTIVE).build();

        freePlan = planEntity("FREE", 3, 5, 120,
            new String[]{"XBET", "FONBET"}, BigDecimal.ZERO);

        proPlan = planEntity("PRO", 15, 30, 60,
            new String[]{"XBET", "FONBET", "OLIMP", "BETCITY", "BETBOOM"},
            new BigDecimal("299.00"));

        activeSub = SubscriptionEntity.builder()
            .id(SUB_ID).user(user).plan(freePlan)
            .status(SubscriptionStatus.ACTIVE).build();
    }

    // ─── getUserPlan ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUserPlan: returns plan DTO mapped from active subscription")
    void getUserPlan_activeSubscription_returnsPlanDto() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));

        SubscriptionPlanDto result = subscriptionService.getUserPlan(TG_ID);

        assertThat(result.code()).isEqualTo("FREE");
        assertThat(result.maxControllers()).isEqualTo(3);
        assertThat(result.allowedBookmakers()).containsExactlyInAnyOrder("XBET", "FONBET");
    }

    @Test
    @DisplayName("getUserPlan: user not found → UserNotFoundException")
    void getUserPlan_userNotFound_throws() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.empty());
        assertThatThrownBy(() -> subscriptionService.getUserPlan(TG_ID))
            .isInstanceOf(UserNotFoundException.class);
    }

    // ─── canAddController ────────────────────────────────────────────────────

    @Test
    @DisplayName("canAddController: below limit → true")
    void canAddController_belowLimit_returnsTrue() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));
        given(controllerRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(2);

        assertThat(subscriptionService.canAddController(TG_ID)).isTrue();
    }

    @Test
    @DisplayName("canAddController: at limit → false")
    void canAddController_atLimit_returnsFalse() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));
        given(controllerRepository.countByUserIdAndIsActiveTrue(USER_ID)).willReturn(3); // FREE max

        assertThat(subscriptionService.canAddController(TG_ID)).isFalse();
    }

    @Test
    @DisplayName("canAddController: user not found → false (safe default)")
    void canAddController_exception_returnsFalse() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.empty());
        assertThat(subscriptionService.canAddController(TG_ID)).isFalse();
    }

    // ─── canUseBookmaker ─────────────────────────────────────────────────────

    @Test
    @DisplayName("canUseBookmaker: allowed bookmaker → true")
    void canUseBookmaker_allowed_returnsTrue() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));

        assertThat(subscriptionService.canUseBookmaker(TG_ID, "XBET")).isTrue();
        assertThat(subscriptionService.canUseBookmaker(TG_ID, "xbet")).isTrue(); // case-insensitive
    }

    @Test
    @DisplayName("canUseBookmaker: not in plan → false")
    void canUseBookmaker_notAllowed_returnsFalse() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));

        assertThat(subscriptionService.canUseBookmaker(TG_ID, "BETBOOM")).isFalse();
    }

    // ─── getPollInterval ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getPollInterval: returns plan's poll interval")
    void getPollInterval_returnsPlanInterval() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));

        assertThat(subscriptionService.getPollInterval(TG_ID)).isEqualTo(120);
    }

    @Test
    @DisplayName("getPollInterval: exception → default 120s")
    void getPollInterval_exception_returnsDefault() {
        given(userRepository.findByTelegramId(TG_ID)).willReturn(Optional.empty());
        assertThat(subscriptionService.getPollInterval(TG_ID)).isEqualTo(120);
    }

    // ─── activatePlan ────────────────────────────────────────────────────────

    @Test
    @DisplayName("activatePlan: cancels current sub, creates new ACTIVE sub")
    void activatePlan_success_cancelsOldAndCreatesNew() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(subscriptionPlanRepository.findByCode("PRO")).willReturn(Optional.of(proPlan));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(activeSub));
        given(subscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity result = subscriptionService.activatePlan(USER_ID, "PRO", "PAY-123");

        assertThat(result.getPlan().getCode()).isEqualTo("PRO");
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getPaymentRef()).isEqualTo("PAY-123");
        assertThat(result.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(29)); // ~30 days
        // old sub was cancelled
        assertThat(activeSub.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    @DisplayName("activatePlan: free plan has no expiry")
    void activatePlan_freePlan_noExpiry() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(subscriptionPlanRepository.findByCode("FREE")).willReturn(Optional.of(freePlan));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.empty());
        given(subscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        SubscriptionEntity result = subscriptionService.activatePlan(USER_ID, "FREE", null);

        assertThat(result.getExpiresAt()).isNull();
    }

    // ─── expireSubscription ──────────────────────────────────────────────────

    @Test
    @DisplayName("expireSubscription: marks EXPIRED, downgrades to FREE, publishes event")
    void expireSubscription_marksExpiredAndDowngrades() {
        SubscriptionEntity proSub = SubscriptionEntity.builder()
            .id(SUB_ID).user(user).plan(proPlan)
            .status(SubscriptionStatus.ACTIVE)
            .expiresAt(OffsetDateTime.now().minusDays(1))
            .build();

        given(subscriptionRepository.findById(SUB_ID)).willReturn(Optional.of(proSub));
        given(subscriptionPlanRepository.findByCode("FREE")).willReturn(Optional.of(freePlan));
        given(subscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        subscriptionService.expireSubscription(SUB_ID);

        assertThat(proSub.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        then(subscriptionRepository).should(times(2)).save(any()); // expire old + save new FREE

        ArgumentCaptor<SubscriptionExpiredEvent> eventCaptor =
            ArgumentCaptor.forClass(SubscriptionExpiredEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        SubscriptionExpiredEvent event = eventCaptor.getValue();
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.telegramId()).isEqualTo(TG_ID);
        assertThat(event.oldPlanCode()).isEqualTo("PRO");
    }

    @Test
    @DisplayName("expireSubscription: subscription not found → IllegalArgumentException")
    void expireSubscription_notFound_throws() {
        given(subscriptionRepository.findById(SUB_ID)).willReturn(Optional.empty());
        assertThatThrownBy(() -> subscriptionService.expireSubscription(SUB_ID))
            .isInstanceOf(IllegalArgumentException.class);
        then(eventPublisher).should(never()).publishEvent(any());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static SubscriptionPlanEntity planEntity(String code, int maxC, int maxF,
                                                      int pollSec, String[] bookmakers,
                                                      BigDecimal price) {
        return SubscriptionPlanEntity.builder()
            .id(UUID.randomUUID()).code(code).name(code + " plan")
            .maxControllers(maxC).maxFilters(maxF).pollIntervalSec(pollSec)
            .allowedBookmakers(bookmakers).notifyChannels(new String[]{"TELEGRAM"})
            .priceRub(price).isActive(true).build();
    }
}
