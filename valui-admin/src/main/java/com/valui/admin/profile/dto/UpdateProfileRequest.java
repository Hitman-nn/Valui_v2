package com.valui.admin.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Поля профиля для обновления (null-поля игнорируются)")
public record UpdateProfileRequest(

        @Schema(description = "Telegram username без @", example = "john_doe")
        @Size(max = 64, message = "username не может быть длиннее 64 символов")
        @Pattern(regexp = "^[a-zA-Z0-9_]*$", message = "username может содержать только латиницу, цифры и _")
        String username,

        @Schema(description = "Код языка интерфейса (ISO 639-1)", example = "ru")
        @Pattern(regexp = "^[a-z]{2}$", message = "languageCode должен быть двухбуквенным кодом ISO 639-1")
        String languageCode
) {}
