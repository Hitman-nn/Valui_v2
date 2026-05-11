package com.valui.admin.users.dto;

import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Краткая информация о пользователе (для списков)")
@Relation(collectionRelation = "users", itemRelation = "user")
public final class AdminUserSummaryDto extends RepresentationModel<AdminUserSummaryDto> {

    @Schema(description = "Внутренний UUID пользователя")
    public final UUID id;

    @Schema(description = "Telegram ID", example = "123456789")
    public final Long telegramId;

    @Schema(description = "Telegram username", example = "john_doe")
    public final String username;

    @Schema(description = "Имя в Telegram", example = "John")
    public final String firstName;

    @Schema(description = "Роль пользователя")
    public final UserRole role;

    @Schema(description = "Статус аккаунта")
    public final UserStatus status;

    @Schema(description = "Баланс токенов", example = "150")
    public final int tokenBalance;

    @Schema(description = "Количество контроллеров пользователя")
    public final long controllersCount;

    @Schema(description = "Дата регистрации")
    public final OffsetDateTime createdAt;

    public AdminUserSummaryDto(UserEntity u, long controllersCount) {
        this.id               = u.getId();
        this.telegramId       = u.getTelegramId();
        this.username         = u.getUsername();
        this.firstName        = u.getFirstName();
        this.role             = u.getRole();
        this.status           = u.getStatus();
        this.tokenBalance     = u.getTokenBalance() != null ? u.getTokenBalance() : 0;
        this.controllersCount = controllersCount;
        this.createdAt        = u.getCreatedAt();
    }
}
