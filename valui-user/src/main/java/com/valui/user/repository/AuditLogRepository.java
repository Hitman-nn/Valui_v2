package com.valui.user.repository;

import com.valui.common.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<AuditLogEntity> findAllByEntityTypeAndEntityId(String entityType, UUID entityId, Pageable pageable);

    Page<AuditLogEntity> findAllByAction(String action, Pageable pageable);

    /** Admin API: events for a specific action within a time window. */
    List<AuditLogEntity> findByActionAndCreatedAtBetween(
            String action, OffsetDateTime from, OffsetDateTime to);
}
