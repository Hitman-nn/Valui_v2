package com.valui.user.repository;

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
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = :vkPeerId WHERE s.userId = :userId")
    void updateVkPeerIdByUserId(@Param("userId") UUID userId, @Param("vkPeerId") Long vkPeerId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = :vkPeerId WHERE s.userId = :userId AND s.chatId = :chatId")
    void updateVkPeerIdByUserIdAndChatId(@Param("userId") UUID userId, @Param("chatId") Long chatId, @Param("vkPeerId") Long vkPeerId);

    @Modifying
    @Query("UPDATE ControllerSubscriptionEntity s SET s.vkPeerId = null WHERE s.userId = :userId")
    void clearVkPeerIdByUserId(@Param("userId") UUID userId);
}
