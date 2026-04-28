package com.valui.user.service;

import com.valui.common.annotation.Audit;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String FREE_PLAN_CODE = "FREE";
    private static final String USERS_CACHE   = "users";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public UserEntity registerOrGetUser(TelegramUserDto dto) {
        return userRepository.findByTelegramId(dto.telegramId())
            .orElseGet(() -> {
                try {
                    return createNewUser(dto);
                } catch (DataIntegrityViolationException e) {
                    // Concurrent registration of the same telegramId — return the winner's record
                    log.warn("Race condition on registerOrGetUser for telegramId={}", dto.telegramId());
                    return userRepository.findByTelegramId(dto.telegramId())
                        .orElseThrow(() -> new IllegalStateException(
                            "User registration race condition unresolved for telegramId=" + dto.telegramId(), e));
                }
            });
    }

    @Override
    @Cacheable(value = USERS_CACHE, key = "#telegramId", unless = "#result == null")
    public Optional<UserEntity> findByTelegramId(Long telegramId) {
        // Spring Cache unwraps Optional<T> before evaluating SpEL, so #result is UserEntity (or null
        // for empty). unless="#result == null" skips caching of Optional.empty() correctly.
        return userRepository.findByTelegramId(telegramId);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, key = "#telegramId")
    public UserEntity updateUsername(Long telegramId, String username) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
        user.setUsername(username);
        log.debug("Username updated: telegramId={} username={}", telegramId, username);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, key = "#telegramId")
    public void updateLanguage(Long telegramId, String languageCode) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
        user.setLanguageCode(languageCode);
        log.debug("Language updated: telegramId={} lang={}", telegramId, languageCode);
        userRepository.save(user);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    @Audit(action = "BAN_USER", entityType = "User")
    public void banUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserBanEvent(userId, "BAN", resolveCurrentAdminId()));
        log.info("🚫 Пользователь заблокирован: userId={}", userId);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    public void unbanUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserBanEvent(userId, "UNBAN", resolveCurrentAdminId()));
        log.info("✅ Пользователь разблокирован: userId={}", userId);
    }

    @Override
    public UserWithSubscriptionDto getUserWithSubscription(Long telegramId) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        SubscriptionEntity subscription = subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(user.getId(), SubscriptionStatus.ACTIVE)
            .orElseThrow(() -> new IllegalStateException(
                "No active subscription found for telegramId=" + telegramId));

        SubscriptionPlanEntity plan = subscription.getPlan();
        return new UserWithSubscriptionDto(user, plan, subscription);
    }

    // ─── private helpers ─────────────────────────────────────────────────────

    private UserEntity createNewUser(TelegramUserDto dto) {
        UserEntity user = UserEntity.builder()
            .telegramId(dto.telegramId())
            .username(dto.username())
            .firstName(dto.firstName())
            .languageCode(dto.languageCode() != null ? dto.languageCode() : "ru")
            .role(UserRole.USER)
            .status(UserStatus.ACTIVE)
            .build();
        user = userRepository.save(user);

        SubscriptionPlanEntity freePlan = subscriptionPlanRepository.findByCode(FREE_PLAN_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "FREE plan not found — check V2__seed_plans.sql migration"));

        SubscriptionEntity subscription = SubscriptionEntity.builder()
            .user(user)
            .plan(freePlan)
            .status(SubscriptionStatus.ACTIVE)
            .build();
        subscriptionRepository.save(subscription);

        log.info("✅ Зарегистрирован пользователь: telegramId={} userId={}", dto.telegramId(), user.getId());
        return user;
    }

    private UUID resolveCurrentAdminId() {
        // TODO: extract from Spring Security context once Telegram auth is implemented
        // SecurityContextHolder.getContext().getAuthentication().getPrincipal()
        return null;
    }
}
