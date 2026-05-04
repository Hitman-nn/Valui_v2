package com.valui.admin.users;

import com.valui.admin.monitoring.ControllerAssembler;
import com.valui.admin.monitoring.dto.ControllerApiDto;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.common.dto.ErrorResponse;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.service.ControllerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.UUID;

@Tag(name = "Admin — Controllers", description = "Управление контроллерами всех пользователей (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/controllers")
@RequiredArgsConstructor
public class AdminMonitoringController {

    private static final String V1 = "application/vnd.valui.v1+json";

    private final ControllerService controllerService;
    private final ControllerAssembler controllerAssembler;
    private final PagedResourcesAssembler<ControllerDto> pagedAssembler;

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
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        Page<ControllerDto> page = controllerService.getAllControllers(pageable);
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
}
