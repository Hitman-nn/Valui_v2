package com.valui.notify.vk;

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

    private final VkProperties  props;
    private final VkApiClient   apiClient;
    private final VkRateLimiter rateLimiter;

    public boolean isEnabled() {
        return props.isEnabled() && !props.getCommunityToken().isBlank();
    }

    /**
     * Sends a plain-text VK message. No-op when VK is disabled.
     * Thread-safe; intended for virtual-thread callers.
     */
    public void send(long peerId, String markdownText) {
        if (!isEnabled()) return;

        String plain = stripMarkdown(markdownText);

        try {
            long totalWaited = 0;
            while (true) {
                long waitMs = rateLimiter.tryAcquire(peerId);
                if (waitMs <= 0) break;
                long sleep = Math.min(waitMs + 10, MAX_RATE_WAIT_MS - totalWaited);
                if (sleep <= 0) {
                    log.warn("[VK] Rate limit exceeded peerId={} — skipping after {}ms", peerId, totalWaited);
                    return;
                }
                Thread.sleep(sleep);
                totalWaited += sleep;
            }
            long msgId = apiClient.sendMessage(peerId, plain);
            if (msgId > 0) {
                log.debug("[VK] Sent to peerId={} msgId={}", peerId, msgId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[VK] Send failed peerId={}: {}", peerId, e.getMessage());
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
