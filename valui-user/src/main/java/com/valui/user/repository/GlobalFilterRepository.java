package com.valui.user.repository;

import com.valui.common.entity.GlobalFilterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GlobalFilterRepository extends JpaRepository<GlobalFilterEntity, UUID> {

    List<GlobalFilterEntity> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    List<GlobalFilterEntity> findAllByUserIdAndChatIdOrderByCreatedAtAsc(UUID userId, Long chatId);

    List<GlobalFilterEntity> findAllByChatIdOrderByCreatedAtAsc(Long chatId);

    List<GlobalFilterEntity> findAllByUserIdAndPausedByTokensFalseOrderByCreatedAtAsc(UUID userId);

    List<GlobalFilterEntity> findAllByUserIdAndPausedByTokensTrue(UUID userId);

    List<GlobalFilterEntity> findAllByChatIdAndPausedByTokensFalseOrderByCreatedAtAsc(Long chatId);

    long countByUserId(UUID userId);

    Optional<GlobalFilterEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<GlobalFilterEntity> findByIdAndChatId(UUID id, Long chatId);

    void deleteByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE GlobalFilterEntity f SET f.pausedByTokens = false WHERE f.user.id = :userId AND f.pausedByTokens = true")
    void unpauseAllByUserId(@Param("userId") UUID userId);

    /** Single query covering both group-member access (chatId) and owner access (userId). */
    @Query("SELECT f FROM GlobalFilterEntity f WHERE f.id = :id AND (f.chatId = :chatId OR f.user.id = :userId)")
    Optional<GlobalFilterEntity> findByIdAndChatIdOrUserId(@Param("id") UUID id,
                                                           @Param("chatId") Long chatId,
                                                           @Param("userId") UUID userId);
}
