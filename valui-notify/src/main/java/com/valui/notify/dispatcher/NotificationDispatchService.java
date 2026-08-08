package com.valui.notify.dispatcher;

import com.valui.common.domain.NotificationChannel;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.sender.EmailNotificationSender;
import com.valui.notify.sender.TelegramNotificationSender;
import com.valui.notify.sender.WebhookNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared dispatch logic used by both {@link NotificationDispatcher} (first attempt)
 * and {@link com.valui.notify.consumer.DlqConsumer} (retries from DLQ).
 *
 * TELEGRAM channel uses {@link TelegramNotificationSender#sendNotification} which
 * attaches inline keyboard buttons when the message carries quick-add or URL metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final TelegramNotificationSender telegramSender;
    private final EmailNotificationSender    emailSender;
    private final WebhookNotificationSender  webhookSender;

    /**
     * Dispatches a new notification.
     *
     * @return Telegram message_id for TELEGRAM channel (used for dedup cache); null for other channels.
     */
    public Integer dispatch(UserNotificationRequestMessage request) throws Exception {
        return switch (NotificationChannel.valueOf(request.channel())) {
            case TELEGRAM -> telegramSender.sendNotification(request);
            case EMAIL    -> { emailSender.send(Long.parseLong(request.userId()), request.messageText()); yield null; }
            case WEBHOOK  -> { webhookSender.send(Long.parseLong(request.userId()), request.messageText()); yield null; }
        };
    }

    /**
     * Edits an already-sent Telegram message in-place (best-effort, no exception thrown).
     * Only applicable to TELEGRAM channel.
     *
     * @return true if the edit was actually attempted and succeeded; false if skipped
     *         (non-Telegram channel, or missing routing info) or if the attempt failed
     */
    public boolean edit(UserNotificationRequestMessage request) {
        if (!NotificationChannel.TELEGRAM.name().equals(request.channel())) return false;
        if (request.editMessageId() == null || request.telegramId() == null) {
            // Reaching this branch at all means the caller already decided editMessageId() was
            // non-null (that's how it routed here) — telegramId() being null despite that is
            // an anomaly worth a trace, not a routine no-op.
            log.warn("[DISPATCH] Edit skipped — telegramId is null, logId={}", request.notificationLogId());
            return false;
        }
        return telegramSender.editNotification(request.telegramId(), request.editMessageId(), request);
    }
}
