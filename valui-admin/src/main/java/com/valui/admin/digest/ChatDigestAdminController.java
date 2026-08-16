package com.valui.admin.digest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Preview/manual-trigger for the weekly per-chat digest — see {@link ChatDigestScheduler}'s
 * javadoc for why this exists: {@code valui.digest.enabled} defaults to {@code false} precisely
 * so real output can be checked here first, against real prod data, before the cron ever fires
 * against every eligible chat at once.
 */
@Slf4j
@Tag(name = "Admin — Digest", description = "Еженедельная статистика по чатам (preview / ручной запуск)")
@RestController
@RequestMapping("/api/v1/admin/digest")
@RequiredArgsConstructor
public class ChatDigestAdminController {

    private final ChatDigestAggregationService aggregationService;
    private final ChatDigestMessageFormatter   formatter;
    private final ChatDigestScheduler          scheduler;

    @Value("${valui.digest.stale-days:30}")
    private int staleDays;

    @Value("${valui.digest.window-days:7}")
    private int windowDays;

    @GetMapping("/preview")
    @Operation(summary = "Посчитать и отрендерить дайджест без отправки — все чаты или один по chatId")
    public ResponseEntity<List<ChatDigestPreviewDto>> preview(
            @RequestParam(required = false) Long chatId) {
        List<ChatDigestStatsDto> digests = aggregationService.buildDigests(staleDays, windowDays);
        List<ChatDigestPreviewDto> result = digests.stream()
                .filter(d -> chatId == null || chatId.equals(d.chatId()))
                .map(d -> new ChatDigestPreviewDto(d.chatId(), formatter.format(d)))
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/trigger")
    @Operation(summary = "Запустить рассылку вручную (dryRun=true по умолчанию — только считает и логирует, не публикует в Kafka)")
    public ResponseEntity<ChatDigestTriggerResultDto> trigger(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (dryRun) {
            int count = aggregationService.buildDigests(staleDays, windowDays).size();
            log.info("[DIGEST] Dry-run trigger: {} chats would receive a digest", count);
            return ResponseEntity.ok(new ChatDigestTriggerResultDto(count, true));
        }
        int count = scheduler.publishAll();
        log.info("[DIGEST] Manual trigger: enqueued {} chat digests", count);
        return ResponseEntity.ok(new ChatDigestTriggerResultDto(count, false));
    }

    public record ChatDigestTriggerResultDto(int chatCount, boolean dryRun) {}
}
