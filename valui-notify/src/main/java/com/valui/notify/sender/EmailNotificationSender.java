package com.valui.notify.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Email sender stub — logs instead of delivering real email.
 *
 * Replace with a Spring Mail integration and remove the {@code @Profile} guard
 * once an SMTP provider is configured for prod.
 */
@Slf4j
@Component
@Profile("!prod")
public class EmailNotificationSender implements NotificationSender {

    @Override
    public void send(Long userId, String messageText) {
        log.info("[EMAIL STUB] userId={} message='{}'", userId, messageText);
    }
}
