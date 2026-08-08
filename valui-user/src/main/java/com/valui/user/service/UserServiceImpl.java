package com.valui.user.service;

import com.valui.common.annotation.Audit;
import com.valui.common.domain.TokenReasonCode;
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

import java.time.OffsetDateTime;
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
    private final TokenLedgerService tokenLedgerService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public UserEntity registerOrGetUser(TelegramUserDto dto) {
        return userRepository.findByTelegramId(dto.telegramId())
            .orElseGet(() -> {
                try {
                    return createNewUser(dto);
                } catch (DataIntegrityViolationException e) {
                    log.warn("Race condition on registerOrGetUser for telegramId={}: {}",
                            dto.telegramId(), e.getMessage());
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
        UUID adminId = resolveCurrentAdminId();
        eventPublisher.publishEvent(new UserBanEvent(userId, "BAN", adminId));
        log.info("[ADMIN] User banned: userId={} adminId={}", userId, adminId);
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
        UUID adminId = resolveCurrentAdminId();
        eventPublisher.publishEvent(new UserBanEvent(userId, "UNBAN", adminId));
        log.info("[ADMIN] User unbanned: userId={} adminId={}", userId, adminId);
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
        UserRole oldRole = user.getRole();
        user.setRole(newRole);
        userRepository.save(user);
        log.info("[ADMIN] Role changed: userId={} adminId={} oldRole={} newRole={}",
                userId, resolveCurrentAdminId(), oldRole, newRole);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    public void setTokenLowThreshold(UUID userId, Integer threshold) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        user.setTokenLowThreshold(threshold);
        userRepository.save(user);
        log.debug("Token threshold updated: userId={} threshold={}", userId, threshold);
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    public UserEntity updateProfile(UUID userId, Integer tokenBalance, Integer tokenLowThreshold, Integer tokenMonthlyGrantRef) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        if (tokenLowThreshold != null)    user.setTokenLowThreshold(tokenLowThreshold);
        if (tokenMonthlyGrantRef != null) user.setTokenMonthlyGrantRef(tokenMonthlyGrantRef);
        user = userRepository.save(user);
        // Balance changes are logged separately by TokenLedgerService.credit/debit below
        // (delta + reason + resulting balance); this covers the two fields that aren't.
        if (tokenLowThreshold != null || tokenMonthlyGrantRef != null) {
            log.info("[ADMIN] Profile updated: userId={} tokenLowThreshold={} tokenMonthlyGrantRef={}",
                    userId, tokenLowThreshold, tokenMonthlyGrantRef);
        }

        if (tokenBalance != null) {
            int current = user.getTokenBalance() != null ? user.getTokenBalance() : 0;
            int delta   = tokenBalance - current;
            if (delta > 0) {
                // credit properly restores paused subscriptions/filters, records ledger tx, resets alert flag
                tokenLedgerService.credit(userId, delta, TokenReasonCode.ADMIN_GRANT, null);
            } else if (delta < 0) {
                // debit pauses everything if balance hits zero
                tokenLedgerService.debit(userId, -delta, TokenReasonCode.ADMIN_DEDUCT, null);
            }
            user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        }
        return user;
    }

    @Override
    @Transactional
    @CacheEvict(value = USERS_CACHE, allEntries = true)
    @Audit(action = "USER_DELETE", entityType = "User")
    public void deleteUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        userRepository.delete(user);
        log.info("[ADMIN] User deleted: userId={} telegramId={} adminId={}",
                userId, user.getTelegramId(), resolveCurrentAdminId());
    }

    @Override
    public List<Long> findTelegramIdsByStatus(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return userRepository.findAllTelegramIds();
        }
        try {
            return userRepository.findTelegramIdsByStatus(UserStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            // This silently widens a broadcast to EVERY user in the DB on a bad status string —
            // worth a WARN since it's the opposite of what an admin who typo'd a status filter
            // would expect (a narrower target, not the broadest possible one).
            log.warn("[USER] Invalid status filter '{}' — falling back to ALL users for broadcast targeting", status);
            return userRepository.findAllTelegramIds();
        }
    }

    @Override
    @Transactional
    public UserEntity setTokenStatsResetAt(UUID userId, OffsetDateTime resetAt) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        user.setTokenStatsResetAt(resetAt);
        log.debug("Token stats reset boundary set: userId={} resetAt={}", userId, resetAt);
        return userRepository.save(user);
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
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.valui.user.security.SecurityPrincipal sp) {
            return sp.userId();
        }
        return null;
    }
}
