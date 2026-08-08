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
        // DEBUG not INFO: this is a stub firing for every EMAIL-channel notification and logging
        // the full message body — at INFO (com.valui is INFO in prod) that's guaranteed spam
        // plus needless content exposure in logs for a channel nobody's actually reading.
        log.debug("[EMAIL STUB] userId={} message='{}'", userId, messageText);
    }
}
