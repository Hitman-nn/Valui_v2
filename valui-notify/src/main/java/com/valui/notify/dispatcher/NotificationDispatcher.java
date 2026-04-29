package com.valui.notify.dispatcher;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.exception.RetryableNotificationException;
import com.valui.notify.log.NotificationLogService;
import com.valui.notify.retry.DeadLetterPublisher;
import com.valui.notify.retry.NotificationRetryPolicy;
import com.valui.user.service.TokenLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * First-attempt consumer: reads from {@code user.notifications.pending}.
 *
 * Перед отправкой проверяет баланс токенов:
 *  - если 0 → пропускает (контроллеры уже muted/paused)
 *  - если > 0 → отправляет и списывает 1 токен за NOTIFICATION_SENT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationDispatchService dispatchService;
    private final NotificationLogService      logService;
    private final DeadLetterPublisher         deadLetterPublisher;
    private final NotificationRetryPolicy     retryPolicy;
    private final TokenLedgerService          tokenLedgerService;

    @KafkaListener(
        topics           = KafkaTopics.USER_NOTIFICATIONS_PENDING,
        groupId          = "valui-notify-dispatch-group",
        containerFactory = "dispatchContainerFactory"
    )
    public void onNotificationPending(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof UserNotificationRequestMessage request)) {
            log.warn("[DISPATCH] Unexpected payload type, skipping");
            return;
        }

        Long telegramId = request.telegramId();
        UUID logId = parseLogId(request.notificationLogId());

        // При нулевом балансе — пропускаем, контроллер уже на паузе
        if (telegramId != null && tokenLedgerService.getBalance(telegramId) == 0) {
            log.debug("[DISPATCH] Пропуск: нулевой баланс telegramId={}", telegramId);
            if (logId != null) logService.markFailed(logId, "Нулевой баланс токенов");
            return;
        }

        try {
            dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId);
            log.debug("[DISPATCH] Отправлено [logId={} channel={}]", logId, request.channel());

            // Списываем токен за успешное уведомление
            if (telegramId != null) {
                int cost = tokenLedgerService.getCost("NOTIFICATION_SENT");
                tokenLedgerService.tryDebit(telegramId, cost, TokenReasonCode.NOTIFICATION_SENT, null);
            }
        } catch (Exception e) {
            log.warn("[DISPATCH] Ошибка [logId={} channel={} telegramId={}]: {}",
                logId, request.channel(), telegramId, e.getMessage());
            if (logId != null) logService.markFailed(logId, e.getMessage());
            RetryableNotificationException rne = retryPolicy.classify(e);
            deadLetterPublisher.publishToDlq(record, rne);
        }
    }

    private static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
