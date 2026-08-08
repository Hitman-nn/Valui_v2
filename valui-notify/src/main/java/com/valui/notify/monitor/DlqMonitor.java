package com.valui.notify.monitor;

import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.service.AdminNotificationService;
import com.valui.notify.vk.VkDeadLetterPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the {@code dlq.final} accumulation counter every 15 minutes.
 * Sends an admin Telegram alert if the count exceeds the threshold.
 *
 * Counter key: {@code dlq:final:count} (incremented by DeadLetterPublisher).
 * Counter is reset by {@code DlqReplayService} after a successful replay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqMonitor {

    static final int ALERT_THRESHOLD = 10;

    private final StringRedisTemplate redisTemplate;
    private final AdminNotificationService adminNotificationService;

    @Scheduled(fixedDelay = 15 * 60 * 1_000L, initialDelay = 60_000L)
    public void checkDlqFinal() {
        long tgCount = getDlqFinalCount();
        log.debug("[DLQ-MONITOR] dlq.final count={}", tgCount);
        if (tgCount > ALERT_THRESHOLD) {
            // Previously the only local trace of this was the Telegram alert itself — if the
            // Telegram delivery path is what's degraded (plausible, same bot infra), there'd be
            // no record anywhere that the threshold was even crossed.
            log.warn("[DLQ-MONITOR] dlq.final threshold exceeded: count={} threshold={}", tgCount, ALERT_THRESHOLD);
            adminNotificationService.alertAdmin(String.format(
                    "⚠️ *DLQ накопился*: %d сообщений в `notifications.dlq.final`.\n"
                    + "Используйте `/api/v1/admin/dlq/replay` для переотправки.", tgCount));
        }

        long vkCount = getVkDlqFinalCount();
        log.debug("[DLQ-MONITOR] vk.dlq.final count={}", vkCount);
        if (vkCount > ALERT_THRESHOLD) {
            log.warn("[DLQ-MONITOR] vk.dlq.final threshold exceeded: count={} threshold={}", vkCount, ALERT_THRESHOLD);
            adminNotificationService.alertAdmin(String.format(
                    "⚠️ *VK DLQ накопился*: %d сообщений в `vk.notifications.dlq.final`.", vkCount));
        }
    }

    public long getDlqFinalCount() {
        String value = redisTemplate.opsForValue().get(DeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
        if (value == null) return 0L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return 0L; }
    }

    public long getVkDlqFinalCount() {
        String value = redisTemplate.opsForValue().get(VkDeadLetterPublisher.DLQ_FINAL_COUNTER_KEY);
        if (value == null) return 0L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return 0L; }
    }
}
