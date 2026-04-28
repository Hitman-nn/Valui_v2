package com.valui.notify.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Webhook sender stub — replace with an HTTP client once webhook endpoints
 * are available in the user profile.
 */
@Slf4j
@Component
public class WebhookNotificationSender implements NotificationSender {

    @Override
    public void send(Long userId, String messageText) {
        log.info("[WEBHOOK STUB] userId={} message='{}'", userId, messageText);
    }
}
