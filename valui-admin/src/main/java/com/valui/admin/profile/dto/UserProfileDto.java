package com.valui.admin.profile.dto;

import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Профиль текущего пользователя")
@Relation(collectionRelation = "profiles", itemRelation = "profile")
public final class UserProfileDto extends RepresentationModel<UserProfileDto> {

    @Schema(description = "Внутренний UUID пользователя", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    public final UUID id;

    @Schema(description = "Telegram ID пользователя", example = "123456789")
    public final Long telegramId;

    @Schema(description = "Telegram username без @", example = "john_doe")
    public final String username;

    @Schema(description = "Имя в Telegram", example = "John")
    public final String firstName;

    @Schema(description = "Код языка интерфейса", example = "ru")
    public final String languageCode;

    @Schema(description = "Роль пользователя")
    public final UserRole role;

    @Schema(description = "Статус аккаунта")
    public final UserStatus status;

    @Schema(description = "Текущий баланс токенов", example = "150")
    public final int tokenBalance;

    @Schema(description = "Процент порога низкого баланса токенов", example = "20")
    public final int tokenLowThresholdPct;

    @Schema(description = "Эталонное значение ежемесячного гранта токенов", example = "200")
    public final int tokenMonthlyGrantRef;

    @Schema(description = "Дата регистрации")
    public final OffsetDateTime createdAt;

    public UserProfileDto(UserEntity u) {
        this.id                  = u.getId();
        this.telegramId          = u.getTelegramId();
        this.username            = u.getUsername();
        this.firstName           = u.getFirstName();
        this.languageCode        = u.getLanguageCode();
        this.role                = u.getRole();
        this.status              = u.getStatus();
        this.tokenBalance        = u.getTokenBalance() != null ? u.getTokenBalance() : 0;
        this.tokenLowThresholdPct = u.getTokenLowThresholdPct() != null ? u.getTokenLowThresholdPct() : 100;
        this.tokenMonthlyGrantRef = u.getTokenMonthlyGrantRef() != null ? u.getTokenMonthlyGrantRef() : 0;
        this.createdAt           = u.getCreatedAt();
    }
}
