package com.valui.admin.profile;

import com.valui.admin.profile.dto.SubscriptionInfoDto;
import com.valui.admin.profile.dto.UpdateProfileRequest;
import com.valui.admin.profile.dto.UserProfileDto;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.common.dto.ErrorResponse;
import com.valui.common.entity.UserEntity;
import com.valui.common.domain.UserStatus;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.UserWithSubscriptionDto;
import com.valui.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Profile", description = "Управление профилем пользователя")
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class UserProfileController {

    static final String V1 = "application/vnd.valui.v1+json";

    private final UserService userService;
    private final UserProfileAssembler assembler;

    // ── GET /api/v1/profile ───────────────────────────────────────────────────

    @Operation(summary = "Получить профиль",
               description = "Возвращает профиль текущего аутентифицированного пользователя.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Профиль получен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<EntityModel<UserProfileDto>> getProfile(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        UserEntity user = requireUser(principal);
        return ResponseEntity.ok(assembler.toModel(user));
    }

    // ── PATCH /api/v1/profile ─────────────────────────────────────────────────

    @Operation(summary = "Обновить профиль",
               description = "Обновляет username и/или languageCode. Null-поля игнорируются.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Профиль обновлён"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping(
            consumes = {V1, MediaType.APPLICATION_JSON_VALUE},
            produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<EntityModel<UserProfileDto>> updateProfile(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest req) {

        Long telegramId = principal.telegramId();

        if (req.username() != null) {
            userService.updateUsername(telegramId, req.username());
        }
        if (req.languageCode() != null) {
            userService.updateLanguage(telegramId, req.languageCode());
        }

        UserEntity updated = requireUser(principal);
        return ResponseEntity.ok(assembler.toModel(updated));
    }

    // ── GET /api/v1/profile/subscription ─────────────────────────────────────

    @Operation(summary = "Текущая подписка",
               description = "Возвращает план и параметры активной подписки пользователя.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Подписка получена"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/subscription", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<SubscriptionInfoDto> getSubscription(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        UserWithSubscriptionDto dto = userService.getUserWithSubscription(principal.telegramId());

        if (dto.subscription() == null || dto.plan() == null) {
            return ResponseEntity.ok(null);
        }

        return ResponseEntity.ok(SubscriptionInfoDto.from(dto.subscription(), dto.plan()));
    }

    // ── GET /api/v1/profile/tokens ────────────────────────────────────────────

    @Operation(summary = "Баланс токенов",
               description = "Текущий баланс, эталонный грант и порог оповещения о низком балансе.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Данные о токенах получены"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/tokens", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<TokenSummaryDto> getTokens(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        UserEntity user = requireUser(principal);
        return ResponseEntity.ok(new TokenSummaryDto(
                user.getTokenBalance()        != null ? user.getTokenBalance()        : 0,
                user.getTokenMonthlyGrantRef() != null ? user.getTokenMonthlyGrantRef() : 0,
                user.getTokenLowThresholdPct() != null ? user.getTokenLowThresholdPct() : 100
        ));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserEntity requireUser(ValuiPrincipal principal) {
        return userService.findByTelegramId(principal.telegramId())
                .orElseThrow(() -> new UserNotFoundException(principal.telegramId()));
    }
}
