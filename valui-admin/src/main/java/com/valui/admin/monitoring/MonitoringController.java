package com.valui.admin.monitoring;

import com.valui.admin.monitoring.dto.ControllerApiDto;
import com.valui.admin.monitoring.dto.CreateControllerApiRequest;
import com.valui.admin.monitoring.dto.UpdateControllerRequest;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.common.dto.ErrorResponse;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.service.ControllerService;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Controllers", description = "Управление контроллерами мониторинга букмекеров")
@RestController
@RequestMapping("/api/v1/controllers")
@RequiredArgsConstructor
public class MonitoringController {

    static final String V1 = "application/vnd.valui.v1+json";

    private final ControllerService service;
    private final ControllerAssembler assembler;
    private final PagedResourcesAssembler<ControllerDto> pagedAssembler;

    // ── GET /api/v1/controllers ───────────────────────────────────────────────

    @Operation(summary = "Список контроллеров",
               description = """
                       Возвращает постраничный список контроллеров текущего пользователя.

                       - Если `chatId` не указан — возвращаются все контроллеры пользователя.
                       - Если `chatId` указан — только контроллеры с уведомлениями в этот чат.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Список получен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<PagedModel<ControllerApiDto>> listControllers(
            @Parameter(description = "Фильтр по Telegram Chat ID (опционально)")
            @RequestParam(required = false) Long chatId,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        Page<ControllerDto> page = service.getUserControllers(principal.telegramId(), pageable);
        return ResponseEntity.ok(pagedAssembler.toModel(page, assembler));
    }

    // ── POST /api/v1/controllers ──────────────────────────────────────────────

    @Operation(summary = "Создать контроллер",
               description = """
                       Создаёт новый контроллер мониторинга.

                       - `chatId` defaults to telegramId пользователя (личный чат).
                       - Букмекер определяется автоматически из URL, если не задан.
                       - Один и тот же URL не может быть добавлен дважды для одного пользователя.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Контроллер создан"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Контроллер для этого URL уже существует",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(
            consumes = {V1, MediaType.APPLICATION_JSON_VALUE},
            produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ControllerApiDto> createController(
            @Valid @RequestBody CreateControllerApiRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        Long chatId = req.chatId() != null ? req.chatId() : principal.telegramId();

        CreateControllerRequest svcReq = new CreateControllerRequest(
                req.url(), req.bookmaker(), req.title(), req.muted(), req.typeHint());

        ControllerDto created = service.addController(svcReq, principal.telegramId(), chatId);

        if (req.filterRule() != null && !req.filterRule().isBlank()) {
            created = service.updateFilterRule(created.id(), principal.telegramId(), req.filterRule());
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();

        return ResponseEntity.created(location).body(assembler.toModel(created));
    }

    // ── GET /api/v1/controllers/{id} ──────────────────────────────────────────

    @Operation(summary = "Получить контроллер",
               description = "Возвращает контроллер по ID. Доступен только владельцу.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Контроллер получен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Нет доступа к чужому контроллеру",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(value = "/{id}", produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ControllerApiDto> getController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        ControllerDto dto = service.getControllerForChat(id, principal.telegramId());
        return ResponseEntity.ok(assembler.toModel(dto));
    }

    // ── PATCH /api/v1/controllers/{id} ────────────────────────────────────────

    @Operation(summary = "Обновить контроллер",
               description = "Обновляет title и/или filterRule. Null-поля игнорируются.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Контроллер обновлён"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping(value = "/{id}",
                  consumes = {V1, MediaType.APPLICATION_JSON_VALUE},
                  produces = {V1, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<ControllerApiDto> updateController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Valid @RequestBody UpdateControllerRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        ControllerDto dto = service.getController(id);
        if (req.filterRule() != null) {
            dto = service.updateFilterRule(id, principal.telegramId(), req.filterRule());
        }
        return ResponseEntity.ok(assembler.toModel(dto));
    }

    // ── DELETE /api/v1/controllers/{id} ───────────────────────────────────────

    @Operation(summary = "Удалить контроллер",
               description = "Останавливает и удаляет контроллер. Операция необратима.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Контроллер удалён"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        service.removeController(id, principal.telegramId());
        return ResponseEntity.noContent().build();
    }

    // ── POST /api/v1/controllers/{id}/mute ───────────────────────────────────

    @Operation(summary = "Заглушить уведомления",
               description = "Временно отключает уведомления контроллера. Данные продолжают собираться.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Контроллер заглушён"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/mute")
    public ResponseEntity<Void> muteController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        service.muteController(id, principal.telegramId());
        return ResponseEntity.noContent().build();
    }

    // ── DELETE /api/v1/controllers/{id}/mute ─────────────────────────────────

    @Operation(summary = "Включить уведомления",
               description = "Возобновляет уведомления заглушённого контроллера.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Уведомления включены"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Контроллер не найден",
                         content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}/mute")
    public ResponseEntity<Void> unmuteController(
            @Parameter(description = "UUID контроллера") @PathVariable UUID id,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {

        service.unmuteController(id, principal.telegramId());
        return ResponseEntity.noContent().build();
    }
}
