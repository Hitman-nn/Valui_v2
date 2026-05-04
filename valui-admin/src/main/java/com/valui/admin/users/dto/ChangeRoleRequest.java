package com.valui.admin.users.dto;

import com.valui.common.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Запрос на смену роли пользователя")
public record ChangeRoleRequest(

        @Schema(description = "Новая роль", example = "ADMIN")
        @NotNull(message = "role обязателен")
        UserRole role
) {}
