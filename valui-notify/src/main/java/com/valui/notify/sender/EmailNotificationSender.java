package com.valui.notify.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Email sender stub — logs instead of delivering real email.
 * Replace with Spring Mail once an SMTP provider is configured.
 */
@Slf4j
@Component
public class EmailNotificationSender implements NotificationSender {

    @Override
    public void send(Long userId, String messageText) {
        log.info("[EMAIL STUB] userId={} message='{}'", userId, messageText);
    }
}
