package com.valui.admin.costs;

import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin — Token Costs", description = "Стоимость токенных операций")
@RestController
@RequestMapping("/api/v1/admin/token-costs")
@RequiredArgsConstructor
public class AdminTokenCostsController {

    private final TokenActionCostService service;

    @GetMapping
    @Operation(summary = "Список всех типов операций и их стоимости")
    public List<TokenActionCostDto> list() {
        return service.listAll();
    }

    @PatchMapping("/{actionCode}")
    @Operation(summary = "Изменить стоимость операции")
    public TokenActionCostDto update(
            @PathVariable String actionCode,
            @Valid @RequestBody UpdateTokenActionCostRequest req,
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        return service.update(actionCode, req, principal.telegramId());
    }
}
