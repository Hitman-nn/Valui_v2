package com.valui.admin.users;

import com.valui.admin.monitoring.ControllerAssembler;
import com.valui.admin.monitoring.dto.ControllerApiDto;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.admin.users.dto.AdminNotificationLogDto;
import com.valui.admin.users.dto.AdminPaymentTransactionDto;
import com.valui.admin.users.dto.AdminUserDto;
import com.valui.admin.users.dto.AdminUserSummaryDto;
import com.valui.admin.users.dto.ChangeRoleRequest;
import com.valui.admin.users.dto.ResetTokenStatsRequest;
import com.valui.admin.users.dto.UpdateUserProfileRequest;
import com.valui.common.domain.UserStatus;
import com.valui.common.dto.ErrorResponse;
import com.valui.admin.audit.dto.AdminAuditDto;
import com.valui.common.entity.UserEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.NotificationLogRepository;
import com.valui.user.repository.PaymentTransactionRepository;
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
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin — Users", description = "Управление пользователями (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private static final String V1 = "application/vnd.valui.v1+json";

    private final UserService userService;
    private final ControllerService controllerService;
    private final ControllerAssembler controllerAssembler;
    private final AdminUserAssembler assembler;
    private final PagedResourcesAssembler<UserEntity> pagedAssembler;
    private final AuditLogRepository auditLogRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    // ── GET /api/v1/admin/users ───────────────────────────────────────────────

    @Operation(summary = "Список пользователей")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Список получен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<PagedModel<AdminUserSummaryDto>> listUsers(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal,
            @RequestParam(required = false) String status,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        UserStatus userStatus = null;
        if (status != null && !status.isBlank()) {
            try { userStatus = UserStatus.valueOf(status.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        Page<UserEntity> page = userService.findAllUsers(userStatus, pageable);
        return ResponseEntity.ok(pagedAssembler.toModel(page, assembler));
    }

    // ── GET /api/v1/admin/users/{id} ──────────────────────────────────────────

    @Operation(summary = "Получить пользователя")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Пользователь найден"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/{id}", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<AdminUserDto> getUser(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        UserEntity user = userService.findById(id);
        AdminUserDto dto = new AdminUserDto(user);
        enrichWithLinks(dto, user);
        return ResponseEntity.ok(dto);
    }

    // ── POST /api/v1/admin/users/{id}/ban ────────────────────────────────────

    @Operation(summary = "Заблокировать пользователя")
    @PostMapping("/{id}/ban")
    public ResponseEntity<Void> banUser(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        userService.banUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── DELETE /api/v1/admin/users/{id}/ban ──────────────────────────────────

    @Operation(summary = "Разблокировать пользователя")
    @DeleteMapping("/{id}/ban")
    public ResponseEntity<Void> unbanUser(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        userService.unbanUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/v1/admin/users/{id}/role ──────────────────────────────────

    @Operation(summary = "Изменить роль пользователя")
    @PatchMapping(value = "/{id}/role", consumes = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> changeRole(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeRoleRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        userService.updateRole(id, req.role());
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/v1/admin/users/{id}/audit-log ───────────────────────────────

    @Operation(summary = "Аудит-лог пользователя")
    @GetMapping(value = "/{id}/audit-log", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Page<AdminAuditDto>> auditLog(
            @PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        return ResponseEntity.ok(
                auditLogRepository.findAllByUserIdOrderByCreatedAtDesc(id, pageable)
                        .map(AdminAuditDto::from));
    }

    // ── GET /api/v1/admin/users/{id}/notifications ───────────────────────────

    @Operation(summary = "История уведомлений пользователя")
    @GetMapping(value = "/{id}/notifications", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Page<AdminNotificationLogDto>> notifications(
            @PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        return ResponseEntity.ok(
                notificationLogRepository.findAllByUserId(id, pageable).map(AdminNotificationLogDto::from));
    }

    // ── DELETE /api/v1/admin/users/{id} ──────────────────────────────────────

    @Operation(summary = "Удалить пользователя (каскадно)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/v1/admin/users/{id}/controllers ──────────────────────────────

    @Operation(summary = "Контроллеры пользователя")
    @GetMapping(value = "/{id}/controllers", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<List<ControllerApiDto>> userControllers(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        UserEntity user = userService.findById(id);
        List<ControllerDto> list = controllerService.getUserControllers(user.getTelegramId());
        return ResponseEntity.ok(list.stream().map(controllerAssembler::toModel).toList());
    }

    // ── PATCH /api/v1/admin/users/{id}/profile ───────────────────────────────

    @Operation(summary = "Обновить профиль пользователя (токены, пороги)")
    @PatchMapping(value = "/{id}/profile", consumes = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<AdminUserDto> updateProfile(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        UserEntity user = userService.updateProfile(id, req.tokenBalance(), req.tokenLowThreshold(), req.tokenMonthlyGrantRef());
        AdminUserDto dto = new AdminUserDto(user);
        enrichWithLinks(dto, user);
        return ResponseEntity.ok(dto);
    }

    // ── POST /api/v1/admin/users/{id}/token-stats/reset ──────────────────────

    @Operation(summary = "Задать нижнюю границу статистики токенов (по умолчанию — сейчас)")
    @PostMapping(value = "/{id}/token-stats/reset",
                 consumes = {V1, MediaType.APPLICATION_JSON_VALUE},
                 produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<AdminUserDto> resetTokenStats(
            @PathVariable UUID id,
            @RequestBody(required = false) ResetTokenStatsRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        OffsetDateTime resetAt = (req != null && req.resetAt() != null)
            ? req.resetAt()
            : OffsetDateTime.now();
        UserEntity user = userService.setTokenStatsResetAt(id, resetAt);
        AdminUserDto dto = new AdminUserDto(user);
        enrichWithLinks(dto, user);
        return ResponseEntity.ok(dto);
    }

    @Operation(summary = "Очистить нижнюю границу статистики токенов (показывать всю историю)")
    @DeleteMapping(value = "/{id}/token-stats/reset",
                   produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<AdminUserDto> clearTokenStatsReset(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        UserEntity user = userService.setTokenStatsResetAt(id, null);
        AdminUserDto dto = new AdminUserDto(user);
        enrichWithLinks(dto, user);
        return ResponseEntity.ok(dto);
    }

    // ── GET /api/v1/admin/users/{id}/payments ─────────────────────────────────

    @Operation(summary = "История платежей пользователя")
    @GetMapping(value = "/{id}/payments", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<List<AdminPaymentTransactionDto>> userPayments(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        return ResponseEntity.ok(
                paymentTransactionRepository.findAllByUserId(id).stream()
                        .map(AdminPaymentTransactionDto::from).toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void enrichWithLinks(AdminUserDto dto, UserEntity user) {
        dto.add(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                .linkTo(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                        .methodOn(AdminUserController.class)
                        .getUser(user.getId(), null)).withSelfRel());

        dto.add(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                .linkTo(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                        .methodOn(AdminUserController.class)
                        .changeRole(user.getId(), null, null)).withRel("changeRole"));

        if (user.getStatus() == UserStatus.BANNED) {
            dto.add(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                    .linkTo(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                            .methodOn(AdminUserController.class)
                            .unbanUser(user.getId(), null)).withRel("unban"));
        } else {
            dto.add(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                    .linkTo(org.springframework.hateoas.server.mvc.WebMvcLinkBuilder
                            .methodOn(AdminUserController.class)
                            .banUser(user.getId(), null)).withRel("ban"));
        }
    }
}
