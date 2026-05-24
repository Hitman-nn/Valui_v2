package com.valui.notify.vk;

import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.stats.NotificationStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Best-effort VK notification sender.
 * Strips Telegram MarkdownV2 escaping, applies rate limiting (1 msg/s per peer_id),
 * then delegates to {@link VkApiClient}.
 *
 * Failures are logged at WARN and swallowed — VK delivery is supplemental to Telegram.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkNotificationSender {

    private static final int MAX_RATE_WAIT_MS = 10_000;

    private final VkProperties       props;
    private final VkApiClient        apiClient;
    private final VkRateLimiter      rateLimiter;
    private final NotificationStats  stats;

    public boolean isEnabled() {
        return props.isEnabled() && !props.getCommunityToken().isBlank();
    }

    /**
     * Best-effort send — swallows all errors. Used for non-critical side-channel calls.
     * Passes {@code random_id=0} so VK skips deduplication — safe for one-off sends
     * that are not part of the retry pipeline.
     */
    public void send(long peerId, String markdownText) {
        try {
            dispatch(peerId, markdownText, 0L);
        } catch (Exception e) {
            log.warn("[VK] Best-effort send failed peerId={}: {}", peerId, e.getMessage());
        }
    }

    /**
     * Reliable send — throws {@link RetryableNotificationException} on failure.
     * Intended for use in the Kafka retry pipeline.
     *
     * @param randomId deterministic dedup key derived from notificationLogId (0 = no dedup)
     */
    public void dispatch(long peerId, String markdownText, long randomId) {
        if (!isEnabled()) return;

        String plain = stripMarkdown(markdownText);

        try {
            long totalWaited = 0;
            while (true) {
                long waitMs = rateLimiter.tryAcquire(peerId);
                if (waitMs <= 0) break;
                long remaining = MAX_RATE_WAIT_MS - totalWaited;
                if (remaining <= 0 || waitMs >= remaining) {
                    stats.incVkSkipped();
                    throw new RetryableNotificationException(
                            "VK rate limit exceeded peerId=" + peerId + " after " + totalWaited + "ms",
                            null, true, 0);
                }
                Thread.sleep(waitMs + 10);
                totalWaited += waitMs + 10;
            }
            // VkApiClient throws RetryableNotificationException on any VK API error
            long msgId = apiClient.sendMessage(peerId, plain, randomId);
            stats.incVkSent();
            log.debug("[VK] Sent peerId={} msgId={}", peerId, msgId);
        } catch (RetryableNotificationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableNotificationException(
                    "VK send interrupted peerId=" + peerId, e, true, 0);
        }
    }

    // Converts Telegram Markdown/MarkdownV2 to plain text for VK.
    static String stripMarkdown(String text) {
        if (text == null) return "";
        // [display text](url) → url
        String r = text.replaceAll("\\[([^\\]]*)]\\(([^)]+)\\)", "$2");
        // *bold* → text
        r = r.replaceAll("\\*([^*\n]+)\\*", "$1");
        // _italic_ → text
        r = r.replaceAll("_([^_\n]+)_", "$1");
        // backslash-escaped chars (MarkdownV2): \. \! \- etc → just the char
        r = r.replaceAll("\\\\([_*\\[\\]()~`>#+\\-=|{}.!])", "$1");
        return r;
    }
}
