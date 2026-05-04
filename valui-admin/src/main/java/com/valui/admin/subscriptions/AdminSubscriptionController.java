package com.valui.admin.subscriptions;

import com.valui.admin.subscriptions.dto.AdminSubscriptionDto;
import com.valui.admin.subscriptions.dto.GrantPlanRequest;
import com.valui.admin.subscriptions.dto.SubscriptionStatsDto;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.user.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin — Subscriptions", description = "Управление подписками (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping
    @Operation(summary = "Список активных подписок (с пагинацией)")
    public ResponseEntity<Page<AdminSubscriptionDto>> listActive(
            @ParameterObject @PageableDefault(size = 20, sort = "startedAt") Pageable pageable) {
        Page<AdminSubscriptionDto> page = subscriptionService.findAllActive(pageable)
                .map(AdminSubscriptionDto::from);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/expiring")
    @Operation(summary = "Подписки, истекающие в ближайшие 24 часа")
    public ResponseEntity<List<AdminSubscriptionDto>> expiringSoon() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AdminSubscriptionDto> result = subscriptionService
                .findExpiringSoon(now, now.plusHours(24))
                .stream()
                .map(AdminSubscriptionDto::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    @Operation(summary = "Статистика подписок по планам")
    public ResponseEntity<SubscriptionStatsDto> stats() {
        OffsetDateTime now = OffsetDateTime.now();
        long totalActive = subscriptionService.findAllActive(Pageable.unpaged()).getTotalElements();
        int expiringIn24h = subscriptionService
                .findExpiringSoon(now, now.plusHours(24))
                .size();
        SubscriptionStatsDto dto = new SubscriptionStatsDto(
                totalActive,
                expiringIn24h,
                subscriptionService.getStatsByPlan()
        );
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/users/{userId}/grant")
    @Operation(summary = "Выдать план пользователю (без оплаты)")
    public ResponseEntity<Void> grantPlan(
            @PathVariable UUID userId,
            @Valid @RequestBody GrantPlanRequest req) {
        subscriptionService.grantPlan(userId, req.planCode());
        return ResponseEntity.noContent().build();
    }
}
