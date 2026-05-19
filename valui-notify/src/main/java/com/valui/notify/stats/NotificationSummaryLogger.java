package com.valui.notify.stats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Logs a one-liner summary of notification activity every 10 minutes.
 * Counters are reset after each log so numbers reflect the last window, not totals.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSummaryLogger {

    private final NotificationStats stats;

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 10)
    public void logSummary() {
        long sent                     = stats.drainSent();
        long dlqRetry                 = stats.drainDlqRetry();
        long dlqFinal                 = stats.drainDlqFinal();
        long rateLimit                = stats.drainRateLimitBackoff();
        long vkSent                   = stats.drainVkSent();
        long vkSkipped                = stats.drainVkSkipped();
        Map<String, Long> byBookmaker = stats.drainSentByBookmaker();

        boolean hasProblems = dlqRetry > 0 || dlqFinal > 0 || rateLimit > 0 || vkSkipped > 0;
        boolean allZero     = sent == 0 && vkSent == 0 && !hasProblems;

        String bkBreakdown = byBookmaker.isEmpty() ? "" :
                " (" + byBookmaker.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(" ")) + ")";
        String vkPart = (vkSent > 0 || vkSkipped > 0)
                ? " VK=" + vkSent + (vkSkipped > 0 ? "(skipped=" + vkSkipped + ")" : "")
                : "";

        String msg = "[SUMMARY 10m] отправлено={}{} DLQ-retry={} DLQ-final={} rate-limit-backoff={}{}";
        if (allZero) {
            log.debug(msg, sent, bkBreakdown, dlqRetry, dlqFinal, rateLimit, vkPart);
        } else if (hasProblems) {
            log.warn(msg, sent, bkBreakdown, dlqRetry, dlqFinal, rateLimit, vkPart);
        } else {
            log.info(msg, sent, bkBreakdown, dlqRetry, dlqFinal, rateLimit, vkPart);
        }
    }
}
