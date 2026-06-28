package com.valui.user.repository;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ControllerSubscriptionRepository
    extends JpaRepository<ControllerSubscriptionEntity, ControllerSubscriptionEntity.SubscriptionId> {

    List<ControllerSubscriptionEntity> findAllByControllerId(UUID controllerId);

    /** Active = not muted AND not paused by tokens. */
    List<ControllerSubscriptionEntity> findAllByControllerIdAndIsMutedFalseAndPausedByTokensFalse(UUID controllerId);

    boolean existsByControllerIdAndIsMutedFalseAndPausedByTokensFalse(UUID controllerId);

    Optional<ControllerSubscriptionEntity> findByControllerIdAndChatId(UUID controllerId, Long chatId);

    List<ControllerSubscriptionEntity> findAllByUserId(UUID userId);

    List<ControllerSubscriptionEntity> findAllByUserIdAndPausedByTokensTrue(UUID userId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.isMuted = :muted WHERE s.controllerId = :cid AND s.chatId = :chatId")
    void updateMuted(@Param("cid") UUID controllerId, @Param("chatId") Long chatId, @Param("muted") boolean muted);

    @Modifying
    @Query("DELETE FROM ControllerSubscriptionEntity s WHERE s.controllerId = :controllerId AND s.chatId = :chatId")
    void deleteByControllerIdAndChatId(@Param("controllerId") UUID controllerId, @Param("chatId") Long chatId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.pausedByTokens = :paused WHERE s.userId = :userId")
    void updatePausedByTokensForUser(@Param("userId") UUID userId, @Param("paused") boolean paused);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.pausedByTokens = :paused WHERE s.controllerId IN :ids")
    void updatePausedByTokensForControllers(@Param("ids") List<UUID> ids, @Param("paused") boolean paused);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = :vkPeerId WHERE s.userId = :userId")
    void updateVkPeerIdByUserId(@Param("userId") UUID userId, @Param("vkPeerId") Long vkPeerId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = :vkPeerId WHERE s.userId = :userId AND s.chatId = :chatId")
    void updateVkPeerIdByUserIdAndChatId(@Param("userId") UUID userId, @Param("chatId") Long chatId, @Param("vkPeerId") Long vkPeerId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = null WHERE s.userId = :userId")
    void clearVkPeerIdByUserId(@Param("userId") UUID userId);

    @Query("SELECT DISTINCT s.chatId FROM ControllerSubscriptionEntity s WHERE s.telegramId = :telegramId AND s.chatId < 0 AND s.isMuted = false AND s.pausedByTokens = false")
    List<Long> findActiveGroupChatIdsByTelegramId(@Param("telegramId") Long telegramId);

    /** All distinct chatIds with at least one active (not muted, not token-paused) subscription for the given bookmaker. */
    @Query("""
           SELECT DISTINCT s.chatId FROM ControllerSubscriptionEntity s
           JOIN ControllerEntity c ON c.id = s.controllerId
           WHERE c.bookmaker = :bookmaker
             AND c.isActive = true AND c.pausedByTokens = false
             AND s.isMuted = false AND s.pausedByTokens = false
           """)
    List<Long> findActiveChatIdsByBookmaker(@Param("bookmaker") BookmakerType bookmaker);

    @Query("SELECT s.chatId FROM ControllerSubscriptionEntity s WHERE s.telegramId = :telegramId AND s.chatId < 0 GROUP BY s.chatId ORDER BY COUNT(s.controllerId) DESC")
    List<Long> findGroupChatIdsSortedByControllerCount(@Param("telegramId") Long telegramId);

    @Query("SELECT (COUNT(s) > 0) FROM ControllerSubscriptionEntity s WHERE s.userId = :userId AND s.chatId = :chatId AND s.vkPeerId IS NOT NULL")
    boolean existsVkLinkedByUserIdAndChatId(@Param("userId") UUID userId, @Param("chatId") Long chatId);

    @Query("SELECT s.vkPeerId FROM ControllerSubscriptionEntity s WHERE s.controllerId = :controllerId AND s.chatId = :chatId AND s.vkPeerId IS NOT NULL")
    Optional<Long> findVkPeerIdByControllerIdAndChatId(@Param("controllerId") UUID controllerId, @Param("chatId") Long chatId);

    /** Returns ANY vkPeerId configured for this chat (regardless of which user's subscription
     *  set it). Used for delivery: if any group member linked VK, all controllers in the chat
     *  send VK notifications, not just the linker's controllers. */
    @Query(value = "SELECT vk_peer_id FROM controller_subscriptions WHERE chat_id = :chatId AND vk_peer_id IS NOT NULL LIMIT 1", nativeQuery = true)
    Optional<Long> findAnyVkPeerIdByChatId(@Param("chatId") Long chatId);

    @Query(value = "SELECT vk_peer_id FROM controller_subscriptions WHERE user_id = :userId AND chat_id = :chatId AND vk_peer_id IS NOT NULL ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    Optional<Long> findFirstVkPeerIdByUserIdAndChatId(@Param("userId") UUID userId, @Param("chatId") Long chatId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = null WHERE s.userId = :userId AND s.chatId = :chatId")
    void clearVkPeerIdByUserIdAndChatId(@Param("userId") UUID userId, @Param("chatId") Long chatId);

    // ── Group chat migration (Telegram basic group → supergroup) ─────────────

    @Modifying
    @Query(value = """
        INSERT INTO controller_subscriptions
            (controller_id, chat_id, user_id, telegram_id, is_muted, paused_by_tokens, vk_peer_id, created_at)
        SELECT controller_id, :newChatId, user_id, telegram_id, is_muted, paused_by_tokens, vk_peer_id, created_at
        FROM controller_subscriptions
        WHERE chat_id = :oldChatId
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int migrateSubscriptionsToNewChat(@Param("oldChatId") Long oldChatId, @Param("newChatId") Long newChatId);

    /** Scoped delete: only removes oldChatId rows that were successfully copied to newChatId. */
    @Modifying
    @Query(value = """
        DELETE FROM controller_subscriptions
        WHERE chat_id = :oldChatId
          AND controller_id IN (
              SELECT controller_id FROM controller_subscriptions WHERE chat_id = :newChatId
          )
        """, nativeQuery = true)
    int deleteOldSubscriptionsAfterMigration(@Param("oldChatId") Long oldChatId, @Param("newChatId") Long newChatId);

    // ── chat_members migration (no JPA entity for that table) ─────────────────

    @Modifying
    @Query(value = """
        INSERT INTO chat_members (chat_id, telegram_id, first_name, username, seen_at)
        SELECT :newChatId, telegram_id, first_name, username, seen_at
        FROM chat_members WHERE chat_id = :oldChatId
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int migrateChatMembersToNewChat(@Param("oldChatId") Long oldChatId, @Param("newChatId") Long newChatId);

    @Modifying
    @Query(value = """
        DELETE FROM chat_members WHERE chat_id = :oldChatId
          AND telegram_id IN (SELECT telegram_id FROM chat_members WHERE chat_id = :newChatId)
        """, nativeQuery = true)
    int deleteOldChatMembersAfterMigration(@Param("oldChatId") Long oldChatId, @Param("newChatId") Long newChatId);
}
