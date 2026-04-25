package com.valui.user.service;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.dto.UserWithSubscriptionDto;
import com.valui.user.event.UserBanEvent;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — unit tests")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserServiceImpl userService;

    private static final Long TELEGRAM_ID = 100500L;
    private static final UUID  USER_ID    = UUID.randomUUID();

    private UserEntity storedUser;
    private SubscriptionPlanEntity freePlan;

    @BeforeEach
    void setUp() {
        freePlan = SubscriptionPlanEntity.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .code("FREE")
            .name("Бесплатный")
            .maxControllers(3)
            .maxFilters(5)
            .build();

        storedUser = UserEntity.builder()
            .id(USER_ID)
            .telegramId(TELEGRAM_ID)
            .username("testuser")
            .firstName("Test")
            .role(UserRole.USER)
            .status(UserStatus.ACTIVE)
            .languageCode("ru")
            .build();
    }

    // ─── registerOrGetUser ────────────────────────────────────────────────────

    @Test
    @DisplayName("registerOrGetUser: new user → saves user + subscription with FREE plan")
    void registerOrGetUser_newUser_createsUserAndSubscription() {
        TelegramUserDto dto = new TelegramUserDto(TELEGRAM_ID, "testuser", "Test", "ru");

        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());
        given(userRepository.save(any(UserEntity.class))).willReturn(storedUser);
        given(subscriptionPlanRepository.findByCode("FREE")).willReturn(Optional.of(freePlan));
        given(subscriptionRepository.save(any(SubscriptionEntity.class)))
            .willAnswer(inv -> inv.getArgument(0));

        UserEntity result = userService.registerOrGetUser(dto);

        assertThat(result.getTelegramId()).isEqualTo(TELEGRAM_ID);
        assertThat(result.getId()).isEqualTo(USER_ID);

        then(userRepository).should().save(any(UserEntity.class));

        ArgumentCaptor<SubscriptionEntity> subCaptor = ArgumentCaptor.forClass(SubscriptionEntity.class);
        then(subscriptionRepository).should().save(subCaptor.capture());
        SubscriptionEntity savedSub = subCaptor.getValue();
        assertThat(savedSub.getPlan().getCode()).isEqualTo("FREE");
        assertThat(savedSub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(savedSub.getUser().getId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("registerOrGetUser: existing user → returns without creating anything")
    void registerOrGetUser_existingUser_returnsExistingWithoutSaving() {
        TelegramUserDto dto = new TelegramUserDto(TELEGRAM_ID, "testuser", "Test", "ru");
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(storedUser));

        UserEntity result = userService.registerOrGetUser(dto);

        assertThat(result).isSameAs(storedUser);
        then(userRepository).should(never()).save(any());
        then(subscriptionRepository).should(never()).save(any());
        then(subscriptionPlanRepository).should(never()).findByCode(any());
    }

    @Test
    @DisplayName("registerOrGetUser: missing FREE plan → throws IllegalStateException")
    void registerOrGetUser_freePlanMissing_throwsIllegalState() {
        TelegramUserDto dto = new TelegramUserDto(TELEGRAM_ID, "u", "F", "ru");
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());
        given(userRepository.save(any())).willReturn(storedUser);
        given(subscriptionPlanRepository.findByCode("FREE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.registerOrGetUser(dto))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FREE plan not found");
    }

    // ─── findByTelegramId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findByTelegramId: delegates to repository")
    void findByTelegramId_found_returnsUser() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(storedUser));

        Optional<UserEntity> result = userService.findByTelegramId(TELEGRAM_ID);

        assertThat(result).isPresent().contains(storedUser);
    }

    @Test
    @DisplayName("findByTelegramId: not found → empty Optional")
    void findByTelegramId_notFound_returnsEmpty() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());

        assertThat(userService.findByTelegramId(TELEGRAM_ID)).isEmpty();
    }

    // ─── updateUsername ───────────────────────────────────────────────────────

    @Test
    @DisplayName("updateUsername: updates and saves user")
    void updateUsername_found_updatesAndReturns() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(storedUser));
        given(userRepository.save(storedUser)).willReturn(storedUser);

        UserEntity result = userService.updateUsername(TELEGRAM_ID, "newname");

        assertThat(result.getUsername()).isEqualTo("newname");
        then(userRepository).should().save(storedUser);
    }

    @Test
    @DisplayName("updateUsername: user not found → UserNotFoundException")
    void updateUsername_notFound_throwsUserNotFoundException() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUsername(TELEGRAM_ID, "x"))
            .isInstanceOf(UserNotFoundException.class);
    }

    // ─── banUser ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("banUser: sets status to BANNED and publishes UserBanEvent")
    void banUser_success_setsBannedAndPublishesEvent() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(storedUser));
        given(userRepository.save(storedUser)).willReturn(storedUser);

        userService.banUser(USER_ID);

        assertThat(storedUser.getStatus()).isEqualTo(UserStatus.BANNED);
        then(userRepository).should().save(storedUser);

        ArgumentCaptor<UserBanEvent> eventCaptor = ArgumentCaptor.forClass(UserBanEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().action()).isEqualTo("BAN");
        assertThat(eventCaptor.getValue().targetUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("banUser: user not found → UserNotFoundException")
    void banUser_notFound_throwsUserNotFoundException() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.banUser(USER_ID))
            .isInstanceOf(UserNotFoundException.class);
        then(eventPublisher).should(never()).publishEvent(any());
    }

    // ─── unbanUser ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unbanUser: sets status to ACTIVE and publishes UserBanEvent")
    void unbanUser_success_setsActiveAndPublishesEvent() {
        storedUser.setStatus(UserStatus.BANNED);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(storedUser));
        given(userRepository.save(storedUser)).willReturn(storedUser);

        userService.unbanUser(USER_ID);

        assertThat(storedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        ArgumentCaptor<UserBanEvent> eventCaptor = ArgumentCaptor.forClass(UserBanEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().action()).isEqualTo("UNBAN");
    }

    // ─── getUserWithSubscription ─────────────────────────────────────────────

    @Test
    @DisplayName("getUserWithSubscription: returns user + plan + subscription")
    void getUserWithSubscription_found_returnsDtoWithAllFields() {
        SubscriptionEntity subscription = SubscriptionEntity.builder()
            .id(UUID.randomUUID())
            .user(storedUser)
            .plan(freePlan)
            .status(SubscriptionStatus.ACTIVE)
            .build();

        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(storedUser));
        given(subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(USER_ID, SubscriptionStatus.ACTIVE))
            .willReturn(Optional.of(subscription));

        UserWithSubscriptionDto result = userService.getUserWithSubscription(TELEGRAM_ID);

        assertThat(result.user()).isEqualTo(storedUser);
        assertThat(result.plan()).isEqualTo(freePlan);
        assertThat(result.subscription().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("getUserWithSubscription: user not found → UserNotFoundException")
    void getUserWithSubscription_userNotFound_throwsException() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserWithSubscription(TELEGRAM_ID))
            .isInstanceOf(UserNotFoundException.class);
    }
}
