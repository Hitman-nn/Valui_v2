package com.valui.user.repository;

import com.valui.common.domain.NotificationStatus;
import com.valui.common.entity.NotificationLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, UUID> {

    Page<NotificationLogEntity> findAllByUserId(UUID userId, Pageable pageable);

    List<NotificationLogEntity> findAllByStatus(NotificationStatus status);

    List<NotificationLogEntity> findAllByEventId(UUID eventId);

    @Transactional
    @Modifying
    @Query("UPDATE NotificationLogEntity n SET n.status = :status, n.attempts = n.attempts + 1 WHERE n.id = :id")
    int incrementAttemptsAndSetStatus(@Param("id") UUID id, @Param("status") NotificationStatus status);

    long countByCreatedAtAfter(OffsetDateTime since);

    long countByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);

    /**
     * Per-chat notification count for a time window — batched across all chats at once via
     * GROUP BY (no per-chat query). A chat with zero notifications in the window simply won't
     * appear in the result rows; callers default missing chatIds to 0.
     */
    @Query("""
           SELECT n.chatId, COUNT(n) FROM NotificationLogEntity n
           WHERE n.chatId IS NOT NULL AND n.createdAt >= :since AND n.createdAt < :until
           GROUP BY n.chatId
           """)
    List<Object[]> countNotificationsByChatBetween(@Param("since") OffsetDateTime since, @Param("until") OffsetDateTime until);
}
