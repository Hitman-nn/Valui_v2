package com.valui.admin.audit.dto;

import com.valui.common.entity.AuditLogEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminAuditDto(
        UUID id,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        String details,
        String ipAddress,
        OffsetDateTime createdAt
) {
    public static AdminAuditDto from(AuditLogEntity e) {
        return new AdminAuditDto(
            e.getId(),
            e.getUser() != null ? e.getUser().getId() : null,
            e.getAction(),
            e.getEntityType(),
            e.getEntityId(),
            e.getDetails(),
            e.getIpAddress(),
            e.getCreatedAt()
        );
    }
}
