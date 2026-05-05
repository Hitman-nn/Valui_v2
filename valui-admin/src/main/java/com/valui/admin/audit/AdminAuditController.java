package com.valui.admin.audit;

import com.valui.admin.audit.dto.AdminAuditDto;
import com.valui.common.entity.AuditLogEntity;
import com.valui.user.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin — Audit Log", description = "История действий администраторов (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @Operation(summary = "Все записи аудита с пагинацией")
    public ResponseEntity<Page<AdminAuditDto>> listAll(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {

        Page<AuditLogEntity> page;
        if (action != null) {
            page = auditLogRepository.findAllByAction(action, pageable);
        } else {
            page = auditLogRepository.findAll(pageable);
        }
        return ResponseEntity.ok(page.map(AdminAuditDto::from));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Все действия над пользователем")
    public ResponseEntity<Page<AdminAuditDto>> byUser(
            @PathVariable UUID userId,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(
            auditLogRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(AdminAuditDto::from));
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Аудит по типу и ID объекта")
    public ResponseEntity<Page<AdminAuditDto>> byEntity(
            @PathVariable String entityType,
            @PathVariable UUID entityId,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(
            auditLogRepository.findAllByEntityTypeAndEntityId(entityType, entityId, pageable)
                .map(AdminAuditDto::from));
    }
}
