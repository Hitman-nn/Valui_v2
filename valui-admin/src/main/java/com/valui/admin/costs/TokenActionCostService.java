package com.valui.admin.costs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.entity.AuditLogEntity;
import com.valui.common.entity.TokenActionCostEntity;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.TokenActionCostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenActionCostService {

    private final TokenActionCostRepository costRepository;
    private final AuditLogRepository        auditLogRepository;
    private final ObjectMapper              objectMapper;

    @Transactional(readOnly = true)
    public List<TokenActionCostDto> listAll() {
        return costRepository.findAll().stream()
            .map(e -> new TokenActionCostDto(e.getActionCode(), e.getCostTokens(), e.getDescription()))
            .toList();
    }

    @Transactional
    public TokenActionCostDto update(String actionCode, UpdateTokenActionCostRequest req, Long adminTelegramId) {
        TokenActionCostEntity entity = costRepository.findById(actionCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Action code not found: " + actionCode));

        int oldCost = entity.getCostTokens();
        entity.setCostTokens(req.costTokens());
        if (req.description() != null) entity.setDescription(req.description());
        costRepository.save(entity);

        log.info("[ADMIN] Token cost updated: code={} {} → {} by adminTelegramId={}",
            actionCode, oldCost, req.costTokens(), adminTelegramId);

        String details;
        try {
            details = objectMapper.writeValueAsString(Map.of(
                "actionCode",      actionCode,
                "oldCost",         oldCost,
                "newCost",         req.costTokens(),
                "adminTelegramId", adminTelegramId));
        } catch (Exception ex) {
            details = "{}";
        }
        auditLogRepository.save(AuditLogEntity.builder()
            .action("UPDATE_TOKEN_ACTION_COST")
            .entityType("TokenActionCost")
            .details(details)
            .build());

        return new TokenActionCostDto(entity.getActionCode(), entity.getCostTokens(), entity.getDescription());
    }
}
