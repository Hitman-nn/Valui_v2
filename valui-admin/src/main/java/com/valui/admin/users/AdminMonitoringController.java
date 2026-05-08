package com.valui.admin.users;

import com.valui.admin.events.dto.AdminEventDto;
import com.valui.admin.monitoring.ControllerAssembler;
import com.valui.admin.monitoring.dto.ControllerApiDto;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.common.dto.ErrorResponse;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import com.valui.user.repository.DetectedEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import com.valui.monitor.history.PollHistoryEntry;
import com.valui.monitor.history.PollHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Admin — Controllers", description = "Управление контроллерами всех пользователей (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/controllers")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private static final String V1 = "application/vnd.valui.v1+json";

    private final ControllerService controllerService;
    private final ControllerAssembler controllerAssembler;
    private final PagedResourcesAssembler<ControllerDto> pagedAssembler;
    private final DetectedEventRepository detectedEventRepository;
    private final PollHistoryService pollHistoryService;

    // ── GET /api/v1/admin/controllers ────────────────────────────────────────

    @Operation(summary = "Все контроллеры",
               description = "Постраничный список всех контроллеров всех пользователей. Только для ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Список получен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<PagedModel<ControllerApiDto>> listAllControllers(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal,
            @RequestParam(required = false) String bookmaker,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Boolean isMuted,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        Page<ControllerDto> page = (bookmaker != null || isActive != null || isMuted != null)
                ? controllerService.getAllControllers(bookmaker, isActive, isMuted, pageable)
                : controllerService.getAllControllers(pageable);
        return ResponseEntity.ok(pagedAssembler.toModel(page, controllerAssembler));
    }

    // ── GET /api/v1/admin/controllers/{id} ───────────────────────────────────

    @Operation(summary = "Получить контроллер",
               description = "Возвращает контроллер по UUID без проверки владельца. Только для ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Контроллер найден"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/{id}", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ControllerApiDto> getController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        ControllerDto dto = controllerService.getController(id);
        return ResponseEntity.ok(controllerAssembler.toModel(dto));
    }

    // ── DELETE /api/v1/admin/controllers/{id} ────────────────────────────────

    @Operation(summary = "Деактивировать контроллер",
               description = """
                       Принудительно деактивирует контроллер без проверки владельца.
                       Публикует ControllerRemovedEvent. Только для ADMIN.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Контроллер деактивирован"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Требуется роль ADMIN",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        controllerService.deactivateController(id);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /api/v1/admin/controllers/{id}/toggle ──────────────────────────

    @Operation(summary = "Переключить активность контроллера")
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ControllerApiDto> toggleController(
            @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        ControllerDto dto = controllerService.getController(id);
        if (dto.isActive()) {
            controllerService.deactivateController(id);
        } else {
            controllerService.activateController(id);
        }
        return ResponseEntity.ok(controllerAssembler.toModel(controllerService.getController(id)));
    }

    // ── PATCH /api/v1/admin/controllers/{id}/mute ────────────────────────────

    @Operation(summary = "Переключить mute контроллера")
    @PatchMapping("/{id}/mute")
    public ResponseEntity<ControllerApiDto> toggleMute(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean muted,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        if (muted) {
            controllerService.muteAdmin(id);
        } else {
            controllerService.unmuteAdmin(id);
        }
        return ResponseEntity.ok(controllerAssembler.toModel(controllerService.getController(id)));
    }

    // ── PATCH /api/v1/admin/controllers/{id} ─────────────────────────────────

    @Operation(summary = "Редактировать контроллер")
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
                  produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ControllerApiDto> updateController(
            @PathVariable UUID id,
            @RequestBody AdminUpdateControllerRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        ControllerDto dto = controllerService.updateAdmin(id, req.title(), req.filterRule(), req.pollIntervalSec());
        return ResponseEntity.ok(controllerAssembler.toModel(dto));
    }

    // ── GET /api/v1/admin/controllers/{id}/events ─────────────────────────────

    @Operation(summary = "События контроллера")
    @GetMapping(value = "/{id}/events", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    @Transactional(readOnly = true)
    public ResponseEntity<Page<AdminEventDto>> controllerEvents(
            @PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        Page<DetectedEventEntity> page =
            detectedEventRepository.findByControllerIdOrderByDetectedAtDesc(id, pageable);
        return ResponseEntity.ok(page.map(AdminEventDto::from));
    }

    // ── GET /api/v1/admin/controllers/{id}/poll-history ───────────────────────

    @Operation(summary = "История последних 5 опросов контроллера")
    @GetMapping("/{id}/poll-history")
    public ResponseEntity<java.util.List<PollHistoryEntry>> pollHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(pollHistoryService.getLast(id));
    }

    // ── PATCH /api/v1/admin/controllers/bulk-interval ────────────────────────

    @Operation(summary = "Массовое обновление интервала опроса")
    @PatchMapping("/bulk-interval")
    public ResponseEntity<Void> bulkUpdateInterval(@RequestBody BulkIntervalRequest req) {
        req.ids().forEach(id -> {
            try {
                controllerService.updateAdmin(id, null, null, req.pollIntervalSec());
            } catch (Exception e) {
                log.warn("[BULK] Failed to update interval for controller {}: {}", id, e.getMessage());
            }
        });
        return ResponseEntity.noContent().build();
    }

    public record AdminUpdateControllerRequest(String title, String filterRule, Integer pollIntervalSec) {}
    public record BulkIntervalRequest(List<UUID> ids, int pollIntervalSec) {}
}
