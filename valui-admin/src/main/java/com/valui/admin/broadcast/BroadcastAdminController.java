package com.valui.admin.broadcast;

import com.valui.admin.notifications.dto.BroadcastRequest;
import com.valui.admin.notifications.dto.BroadcastResultDto;
import com.valui.common.kafka.AdminBroadcastMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.user.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Admin — Broadcast", description = "Массовая рассылка (только ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/broadcast")
@RequiredArgsConstructor
public class BroadcastAdminController {

    private final SubscriptionService subscriptionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @PostMapping
    @Operation(summary = "Запустить рассылку")
    public ResponseEntity<BroadcastResultDto> broadcast(@Valid @RequestBody BroadcastRequest req) {
        String planFilter = (req.planCode() == null || req.planCode().equalsIgnoreCase("ALL"))
            ? null : req.planCode().toUpperCase();

        List<Long> telegramIds = subscriptionService.findActiveTelegramIdsByPlan(planFilter);
        for (Long chatId : telegramIds) {
            kafkaTemplate.send(KafkaTopics.ADMIN_BROADCAST, String.valueOf(chatId),
                new AdminBroadcastMessage(chatId, req.text()));
        }

        log.info("[BROADCAST] Queued {} messages, planFilter={}", telegramIds.size(), planFilter);
        return ResponseEntity.ok(new BroadcastResultDto(telegramIds.size(),
            planFilter != null ? planFilter : "ALL"));
    }

    @GetMapping("/preview")
    @Operation(summary = "Оценить аудиторию рассылки")
    public ResponseEntity<BroadcastResultDto> preview(
            @RequestParam(required = false) String planCode) {
        String planFilter = (planCode == null || planCode.equalsIgnoreCase("ALL"))
            ? null : planCode.toUpperCase();
        List<Long> telegramIds = subscriptionService.findActiveTelegramIdsByPlan(planFilter);
        return ResponseEntity.ok(new BroadcastResultDto(telegramIds.size(),
            planFilter != null ? planFilter : "ALL"));
    }
}
