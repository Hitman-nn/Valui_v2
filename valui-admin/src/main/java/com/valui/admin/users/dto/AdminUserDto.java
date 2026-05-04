package com.valui.admin.users.dto;

import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Полная информация о пользователе (admin view)")
@Relation(itemRelation = "user")
public final class AdminUserDto extends RepresentationModel<AdminUserDto> {

    @Schema(description = "Внутренний UUID пользователя")
    public final UUID id;

    @Schema(description = "Telegram ID", example = "123456789")
    public final Long telegramId;

    @Schema(description = "Telegram username", example = "john_doe")
    public final String username;

    @Schema(description = "Имя в Telegram", example = "John")
    public final String firstName;

    @Schema(description = "Код языка", example = "ru")
    public final String languageCode;

    @Schema(description = "Роль пользователя")
    public final UserRole role;

    @Schema(description = "Статус аккаунта")
    public final UserStatus status;

    @Schema(description = "Баланс токенов", example = "150")
    public final int tokenBalance;

    @Schema(description = "Ежемесячный грант токенов", example = "200")
    public final int tokenMonthlyGrantRef;

    @Schema(description = "Порог низкого баланса токенов в %", example = "20")
    public final int tokenLowThresholdPct;

    @Schema(description = "Код активного плана", example = "PRO", nullable = true)
    public final String planCode;

    @Schema(description = "Название активного плана", example = "Pro", nullable = true)
    public final String planName;

    @Schema(description = "Максимальное число контроллеров по плану", example = "10")
    public final int maxControllers;

    @Schema(description = "Дата окончания подписки (null = бессрочно)", nullable = true)
    public final OffsetDateTime subscriptionExpiresAt;

    @Schema(description = "Дата начала подписки", nullable = true)
    public final OffsetDateTime subscriptionStartedAt;

    @Schema(description = "Дата регистрации")
    public final OffsetDateTime createdAt;

    @Schema(description = "Дата последнего обновления профиля")
    public final OffsetDateTime updatedAt;

    public AdminUserDto(UserEntity u, SubscriptionPlanEntity plan, SubscriptionEntity sub) {
        this.id                   = u.getId();
        this.telegramId           = u.getTelegramId();
        this.username             = u.getUsername();
        this.firstName            = u.getFirstName();
        this.languageCode         = u.getLanguageCode();
        this.role                 = u.getRole();
        this.status               = u.getStatus();
        this.tokenBalance         = u.getTokenBalance()         != null ? u.getTokenBalance()         : 0;
        this.tokenMonthlyGrantRef = u.getTokenMonthlyGrantRef() != null ? u.getTokenMonthlyGrantRef() : 0;
        this.tokenLowThresholdPct = u.getTokenLowThresholdPct() != null ? u.getTokenLowThresholdPct() : 100;
        this.createdAt            = u.getCreatedAt();
        this.updatedAt            = u.getUpdatedAt();

        if (plan != null) {
            this.planCode      = plan.getCode();
            this.planName      = plan.getName();
            this.maxControllers = plan.getMaxControllers();
        } else {
            this.planCode       = null;
            this.planName       = null;
            this.maxControllers = 0;
        }

        if (sub != null) {
            this.subscriptionStartedAt = sub.getStartedAt();
            this.subscriptionExpiresAt = sub.getExpiresAt();
        } else {
            this.subscriptionStartedAt = null;
            this.subscriptionExpiresAt = null;
        }
    }
}
