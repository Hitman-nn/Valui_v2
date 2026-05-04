package com.valui.admin.profile.dto;

import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Информация о текущей подписке пользователя")
public record SubscriptionInfoDto(

        @Schema(description = "UUID подписки")
        UUID subscriptionId,

        @Schema(description = "Код плана", example = "PRO")
        String planCode,

        @Schema(description = "Название плана", example = "Pro")
        String planName,

        @Schema(description = "Цена плана в рублях", example = "299.00")
        BigDecimal priceRub,

        @Schema(description = "Максимальное число контроллеров", example = "10")
        int maxControllers,

        @Schema(description = "Интервал опроса в секундах", example = "60")
        int pollIntervalSec,

        @Schema(description = "Ежемесячный грант токенов", example = "200")
        int monthlyTokenGrant,

        @Schema(description = "Активна ли подписка")
        boolean active,

        @Schema(description = "Дата начала подписки")
        OffsetDateTime startedAt,

        @Schema(description = "Дата окончания (null = бессрочно)")
        OffsetDateTime expiresAt
) {
    public static SubscriptionInfoDto from(SubscriptionEntity sub, SubscriptionPlanEntity plan) {
        return new SubscriptionInfoDto(
                sub.getId(),
                plan.getCode(),
                plan.getName(),
                plan.getPriceRub(),
                plan.getMaxControllers(),
                plan.getPollIntervalSec(),
                plan.getMonthlyTokenGrant() != null ? plan.getMonthlyTokenGrant() : 0,
                true,
                sub.getStartedAt(),
                sub.getExpiresAt()
        );
    }
}
