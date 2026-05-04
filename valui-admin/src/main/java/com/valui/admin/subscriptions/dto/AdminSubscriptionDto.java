package com.valui.admin.subscriptions.dto;

import com.valui.common.entity.SubscriptionEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Подписка пользователя (admin view)")
public record AdminSubscriptionDto(
        @Schema(description = "UUID подписки") UUID id,
        @Schema(description = "UUID пользователя") UUID userId,
        @Schema(description = "Telegram ID пользователя") Long telegramId,
        @Schema(description = "Username пользователя") String username,
        @Schema(description = "Код плана", example = "PRO") String planCode,
        @Schema(description = "Название плана") String planName,
        @Schema(description = "Статус подписки") String status,
        @Schema(description = "Дата начала") OffsetDateTime startedAt,
        @Schema(description = "Дата окончания (null = бессрочно)") OffsetDateTime expiresAt,
        @Schema(description = "Ссылка на платёж") String paymentRef
) {
    public static AdminSubscriptionDto from(SubscriptionEntity s) {
        return new AdminSubscriptionDto(
                s.getId(),
                s.getUser().getId(),
                s.getUser().getTelegramId(),
                s.getUser().getUsername(),
                s.getPlan().getCode(),
                s.getPlan().getName(),
                s.getStatus().name(),
                s.getStartedAt(),
                s.getExpiresAt(),
                s.getPaymentRef()
        );
    }
}
