package com.valui.user.service;

import com.valui.common.annotation.Audit;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.event.UserBanEvent;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String USERS_CACHE = "users";

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public UserEntity registerOrGetUser(TelegramUserDto dto) {
        return userRepository.findByTelegramId(dto.telegramId())
            .orElseGet(() -> {
                try {
                    return createNewUser(dto);
                } catch (DataIntegrityViolationException e) {
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

    // ─── Admin-only operations ────────────────────────────────────────────────

    @Override
    public Page<UserEntity> findAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Override
    public Page<UserEntity> findAllUsers(UserStatus status, Pageable pageable) {
        return status != null
                ? userRepository.findAllByStatus(status, pageable)
                : userRepository.findAll(pageable);
    }

    @Override
    public UserEntity findById(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    @Audit(action = "UPDATE_ROLE", entityType = "User")
    public void updateRole(UUID userId, UserRole newRole) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        user.setRole(newRole);
        userRepository.save(user);
        log.info("[ADMIN] Role changed: userId={} newRole={}", userId, newRole);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    public UserEntity updateProfile(UUID userId, Integer tokenBalance, Integer tokenLowThreshold, Integer tokenMonthlyGrantRef) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        if (tokenBalance != null)        user.setTokenBalance(tokenBalance);
        if (tokenLowThreshold != null)   user.setTokenLowThreshold(tokenLowThreshold);
        if (tokenMonthlyGrantRef != null) user.setTokenMonthlyGrantRef(tokenMonthlyGrantRef);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    @Audit(action = "USER_DELETE", entityType = "User")
    public void deleteUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        userRepository.delete(user);
        log.info("[ADMIN] User deleted: userId={} telegramId={}", userId, user.getTelegramId());
    }

    @Override
    public List<Long> findTelegramIdsByStatus(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return userRepository.findAllTelegramIds();
        }
        try {
            return userRepository.findTelegramIdsByStatus(UserStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return userRepository.findAllTelegramIds();
        }
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
        log.info("✅ Зарегистрирован пользователь: telegramId={} userId={}", dto.telegramId(), user.getId());
        return user;
    }

    private UUID resolveCurrentAdminId() {
        return null;
    }
}
