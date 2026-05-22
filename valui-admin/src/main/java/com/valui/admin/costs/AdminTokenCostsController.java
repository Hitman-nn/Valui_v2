package com.valui.admin.costs;

import com.valui.common.entity.TokenActionCostEntity;
import com.valui.user.repository.TokenActionCostRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Admin — Token Costs", description = "Стоимость токенных операций")
@RestController
@RequestMapping("/api/v1/admin/token-costs")
@RequiredArgsConstructor
public class AdminTokenCostsController {

    private final TokenActionCostRepository repository;

    @GetMapping
    @Operation(summary = "Список всех типов операций и их стоимости")
    public List<TokenActionCostDto> list() {
        return repository.findAll().stream()
            .map(e -> new TokenActionCostDto(e.getActionCode(), e.getCostTokens(), e.getDescription()))
            .toList();
    }

    @PatchMapping("/{actionCode}")
    @Operation(summary = "Изменить стоимость операции")
    public TokenActionCostDto update(
            @PathVariable String actionCode,
            @Valid @RequestBody UpdateTokenActionCostRequest req) {
        TokenActionCostEntity entity = repository.findById(actionCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action code not found: " + actionCode));
        entity.setCostTokens(req.costTokens());
        if (req.description() != null) entity.setDescription(req.description());
        repository.save(entity);
        return new TokenActionCostDto(entity.getActionCode(), entity.getCostTokens(), entity.getDescription());
    }
}
