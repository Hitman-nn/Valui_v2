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

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, UUID> {

    Page<NotificationLogEntity> findAllByUserId(UUID userId, Pageable pageable);

    List<NotificationLogEntity> findAllByStatus(NotificationStatus status);

    List<NotificationLogEntity> findAllByEventId(UUID eventId);

    @Modifying
    @Query("UPDATE NotificationLogEntity n SET n.status = :status, n.attempts = n.attempts + 1 WHERE n.id = :id")
    int incrementAttemptsAndSetStatus(@Param("id") UUID id, @Param("status") NotificationStatus status);
}
