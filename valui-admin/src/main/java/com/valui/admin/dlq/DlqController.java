package com.valui.admin.dlq;

import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin operations for the dead-letter queue.
 *
 * All endpoints require ADMIN role (enforced by WebSecurityConfig pattern
 * {@code /api/v1/admin/**} → hasRole('ADMIN')).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "DLQ Admin", description = "Dead-letter queue monitoring and replay")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DlqController {

    private final DlqReplayService dlqReplayService;

    /**
     * Re-publishes all messages from {@code notifications.dlq.final} to their
     * original topics so they get a fresh dispatch attempt.
     * Resets the dlq.final accumulation counter on success.
     */
    @PostMapping("/replay")
    @Operation(summary = "Replay all messages from dlq.final back into processing")
    public ResponseEntity<Map<String, Object>> replay(
            @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        log.info("[DLQ-ADMIN] Manual replay triggered by adminTelegramId={}", principal.telegramId());
        long count = dlqReplayService.replay();
        log.info("[DLQ-ADMIN] Replay completed: adminTelegramId={} dispatched={}", principal.telegramId(), count);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "replayed", count
        ));
    }

    /**
     * Returns the current dlq.final accumulation count, total replayed messages,
     * and an overall health status (OK / WARNING / CRITICAL).
     */
    @GetMapping("/stats")
    @Operation(summary = "DLQ queue sizes and error counts")
    public ResponseEntity<DlqStatsDto> stats() {
        return ResponseEntity.ok(dlqReplayService.stats());
    }
}
