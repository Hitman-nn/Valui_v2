package com.valui.notify.monitor;

import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.service.AdminNotificationService;
import com.valui.notify.vk.VkDeadLetterPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

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

    // Edge-detection per counter (Telegram vs VK): without this, a DLQ that stays above the
    // threshold across many consecutive 15-minute ticks re-sends the same alert every tick until
    // someone replays it. Alert once on crossing into "elevated", stay quiet while it remains
    // elevated, and announce it again once it drops back below the threshold (so "forgot to
    // replay" and "replayed, back to normal" are both visible without being noisy in between).
    private final AtomicBoolean tgElevated = new AtomicBoolean(false);
    private final AtomicBoolean vkElevated = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 15 * 60 * 1_000L, initialDelay = 60_000L)
    public void checkDlqFinal() {
        long tgCount = getDlqFinalCount();
        log.debug("[DLQ-MONITOR] dlq.final count={}", tgCount);
        checkThreshold("dlq.final", tgCount, tgElevated,
                "⚠️ *DLQ накопился*: %d сообщений в `notifications.dlq.final`.\n"
                + "Используйте `/api/v1/admin/dlq/replay` для переотправки.",
                "✅ *DLQ разобран*: `notifications.dlq.final` снова ниже порога (%d).");

        long vkCount = getVkDlqFinalCount();
        log.debug("[DLQ-MONITOR] vk.dlq.final count={}", vkCount);
        checkThreshold("vk.dlq.final", vkCount, vkElevated,
                "⚠️ *VK DLQ накопился*: %d сообщений в `vk.notifications.dlq.final`.",
                "✅ *VK DLQ разобран*: `vk.notifications.dlq.final` снова ниже порога (%d).");
    }

    private void checkThreshold(String label, long count, AtomicBoolean elevated,
                                 String aboveFormat, String recoveredFormat) {
        if (count > ALERT_THRESHOLD) {
            // Previously the only local trace of this was the Telegram alert itself — if the
            // Telegram delivery path is what's degraded (plausible, same bot infra), there'd be
            // no record anywhere that the threshold was even crossed.
            log.warn("[DLQ-MONITOR] {} threshold exceeded: count={} threshold={}", label, count, ALERT_THRESHOLD);
            if (elevated.compareAndSet(false, true)) {
                adminNotificationService.alertAdmin(String.format(aboveFormat, count));
            }
        } else if (elevated.compareAndSet(true, false)) {
            log.info("[DLQ-MONITOR] {} back under threshold: count={}", label, count);
            adminNotificationService.alertAdmin(String.format(recoveredFormat, count));
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
