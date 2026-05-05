package com.valui.admin.events.dto;

import com.valui.common.entity.DetectedEventEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminEventDto(
        UUID id,
        UUID controllerId,
        String controllerTitle,
        String bookmaker,
        String eventExternalId,
        String title,
        String url,
        OffsetDateTime detectedAt,
        OffsetDateTime expiresAt
) {
    public static AdminEventDto from(DetectedEventEntity e) {
        return new AdminEventDto(
            e.getId(),
            e.getController() != null ? e.getController().getId() : null,
            e.getController() != null ? e.getController().getTitle() : null,
            e.getController() != null && e.getController().getBookmaker() != null
                ? e.getController().getBookmaker().name() : null,
            e.getEventExternalId(),
            e.getTitle(),
            e.getUrl(),
            e.getDetectedAt(),
            e.getExpiresAt()
        );
    }
}
