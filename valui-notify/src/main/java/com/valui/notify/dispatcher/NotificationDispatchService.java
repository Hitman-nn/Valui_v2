package com.valui.notify.dispatcher;

import com.valui.common.domain.NotificationChannel;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.sender.EmailNotificationSender;
import com.valui.notify.sender.TelegramNotificationSender;
import com.valui.notify.sender.WebhookNotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Shared dispatch logic used by both {@link NotificationDispatcher} (first attempt)
 * and {@link com.valui.notify.consumer.DlqConsumer} (retries from DLQ).
 *
 * TELEGRAM channel uses {@link TelegramNotificationSender#sendNotification} which
 * attaches inline keyboard buttons when the message carries quick-add or URL metadata.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final TelegramNotificationSender telegramSender;
    private final EmailNotificationSender    emailSender;
    private final WebhookNotificationSender  webhookSender;

    public void dispatch(UserNotificationRequestMessage request) throws Exception {
        switch (NotificationChannel.valueOf(request.channel())) {
            case TELEGRAM -> telegramSender.sendNotification(request);
            case EMAIL    -> emailSender.send(Long.parseLong(request.userId()), request.messageText());
            case WEBHOOK  -> webhookSender.send(Long.parseLong(request.userId()), request.messageText());
        }
    }
}
