package com.valui.user.service;

import com.valui.common.domain.UserRole;
import com.valui.common.entity.UserEntity;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.dto.UserWithSubscriptionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface UserService {

    /**
     * Returns existing user or registers a new one with FREE plan.
     * Idempotent: calling with the same telegramId always returns the same user.
     */
    UserEntity registerOrGetUser(TelegramUserDto dto);

    Optional<UserEntity> findByTelegramId(Long telegramId);

    UserEntity updateUsername(Long telegramId, String username);

    /** Updates the user's preferred language code (e.g. "ru", "en"). Evicts the users cache. */
    void updateLanguage(Long telegramId, String languageCode);

    /** Only ADMIN role. */
    void banUser(UUID userId);

    /** Only ADMIN role. */
    void unbanUser(UUID userId);

    UserWithSubscriptionDto getUserWithSubscription(Long telegramId);

    // ── Admin-only ────────────────────────────────────────────────────────────

    /** Paginated list of all users (admin only). */
    Page<UserEntity> findAllUsers(Pageable pageable);

    /** Get user by internal UUID (admin only). Throws UserNotFoundException if absent. */
    UserEntity findById(UUID userId);

    /** Change the user's role (admin only). Evicts user cache. */
    void updateRole(UUID userId, UserRole newRole);
}
