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

    private static final int MAX_RATE_WAIT_MS = 2_000;

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
            long waitMs = rateLimiter.tryAcquire(peerId);
            if (waitMs > 0) {
                Thread.sleep(Math.min(waitMs + 10, MAX_RATE_WAIT_MS));
                waitMs = rateLimiter.tryAcquire(peerId);
                if (waitMs > 0) {
                    log.warn("[VK] Rate limit exceeded peerId={} — skipping", peerId);
                    return;
                }
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

    // Removes MarkdownV2 backslash escaping so VK receives plain text.
    static String stripMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("\\\\([_*\\[\\]()~`>#+\\-=|{}.!])", "$1");
    }
}
