package com.valui.user.api;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface: stable contract for controller data access, owned by valui-user.
 * Consumed by valui-monitor; hides JPA repository details behind a versioned API.
 */
public interface ControllerPortService {

    List<ControllerEntity> findAllActive();

    List<ControllerEntity> findAllActiveWithUser();

    List<ControllerEntity> findAllActiveByUserId(UUID userId);

    List<ControllerEntity> findAllActiveByUserIdWithUser(UUID userId);

    Optional<ControllerEntity> findById(UUID controllerId);

    Optional<ControllerEntity> findByIdAndUserId(UUID controllerId, UUID userId);

    ControllerEntity save(ControllerEntity entity);

    void updateLastCheckedAt(UUID controllerId, OffsetDateTime checkedAt);

    /** Resets lastCheckedAt to null for all given controller IDs in one UPDATE. */
    void resetLastCheckedAtBatch(List<UUID> controllerIds);

    void updateIsActive(UUID controllerId, boolean active);

    boolean existsByUserAndBookmakerAndUrl(UUID userId, BookmakerType bookmaker, String url);

    Page<ControllerEntity> findByUserIdPageable(UUID userId, Pageable pageable);

    List<ControllerEntity> findAllActiveByNotificationChatId(Long chatId);

    // Subscription methods
    void createSubscription(UUID controllerId, Long chatId, UUID userId, Long telegramId);
    void removeSubscription(UUID controllerId, Long chatId);
    void muteSubscription(UUID controllerId, Long chatId);
    void unmuteSubscription(UUID controllerId, Long chatId);
    /** Returns subscriptions that are NOT muted and NOT paused by tokens. */
    List<ControllerSubscriptionEntity> findActiveSubscriptions(UUID controllerId);
    /** True if at least one subscription is active (not muted, not paused by tokens). */
    boolean hasActiveSubscriptions(UUID controllerId);
    Optional<ControllerSubscriptionEntity> findSubscription(UUID controllerId, Long chatId);

    /** Links VK peer_id to all subscriptions of the user from the given Telegram chat. */
    void linkVk(UUID userId, long telegramChatId, long vkPeerId);

    /** Removes VK peer_id from all subscriptions of the user. */
    void unlinkVk(UUID userId);

    /** Returns distinct group chat IDs (chatId < 0) where the user has active (non-muted, non-paused) subscriptions. */
    List<Long> findActiveGroupChatIds(long telegramId);

    /** Returns the group chat (chatId < 0) where the user has the most controllers, or empty if none. */
    Optional<Long> findGroupChatWithMostControllers(long telegramId);

    /** True if the user has at least one subscription in the given chat with a VK peer_id set. */
    boolean hasVkLinked(UUID userId, long chatId);

    /** Removes VK peer_id from subscriptions of the user in the given chat only. */
    void unlinkVkForChat(UUID userId, long chatId);

    /** Admin use: paginated listing of all controllers across all users. */
    Page<ControllerEntity> findAllPageable(Pageable pageable);

    /** Admin use: filtered paginated listing. Any null param means "no filter". */
    Page<ControllerEntity> findAllFiltered(BookmakerType bookmaker, Boolean isActive, Boolean isMuted, Pageable pageable);
}
