package com.valui.admin.users.dto;

import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.entity.NotificationLogEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminNotificationLogDto(
        UUID id,
        UUID userId,
        UUID eventId,
        NotificationChannel channel,
        NotificationStatus status,
        Integer attempts,
        String errorMessage,
        Long chatId,
        OffsetDateTime sentAt,
        OffsetDateTime createdAt
) {
    public static AdminNotificationLogDto from(NotificationLogEntity e) {
        return new AdminNotificationLogDto(
                e.getId(),
                e.getUser() != null ? e.getUser().getId() : null,
                e.getEvent() != null ? e.getEvent().getId() : null,
                e.getChannel(),
                e.getStatus(),
                e.getAttempts(),
                e.getErrorMessage(),
                e.getChatId(),
                e.getSentAt(),
                e.getCreatedAt()
        );
    }
}
