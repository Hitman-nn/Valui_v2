package com.valui.admin.migration;

import com.valui.monitor.migration.MigrationService;
import com.valui.monitor.migration.dto.DryRunResultDto;
import com.valui.monitor.migration.dto.MigrationRequest;
import com.valui.monitor.migration.dto.MigrationResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Admin — Migration", description = "Импорт контроллеров из legacy chatConfig.json")
@RestController
@RequestMapping("/api/v1/admin/migration")
@RequiredArgsConstructor
public class AdminMigrationController {

    private final MigrationService migrationService;

    @Operation(summary = "Предварительный просмотр — без записи в БД")
    @PostMapping("/dry-run")
    public DryRunResultDto dryRun(@RequestBody MigrationRequest req) {
        log.info("[MIGRATION] DryRun: controllers={}", req.controllers().size());
        return migrationService.dryRun(req);
    }

    @Operation(summary = "Выполнить миграцию: создать контроллеры + засеять dedup в Redis")
    @PostMapping("/execute")
    public MigrationResultDto execute(@RequestBody MigrationRequest req) {
        log.info("[MIGRATION] Execute: controllers={}", req.controllers().size());
        return migrationService.execute(req);
    }
}
