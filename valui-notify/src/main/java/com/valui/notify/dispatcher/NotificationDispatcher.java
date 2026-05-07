package com.valui.notify.dispatcher;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.kafka.KafkaTopics;
import com.valui.common.kafka.UserNotificationRequestMessage;
import com.valui.notify.dedup.TitleDedupCacheService;
import com.valui.notify.dedup.TitleDedupEntry;
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
    private final TitleDedupCacheService      titleDedupCache;

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

        // ── Edit path: update existing Telegram message, no token cost, no log entry ──
        // Telegram editMessageText is idempotent, so replaying this record (Kafka rebalance
        // before offset commit) just re-edits the same message — safe.
        if (request.editMessageId() != null) {
            dispatchService.edit(request);
            log.debug("[DISPATCH] Отредактировано [chatId={} messageId={}]",
                    request.telegramId(), request.editMessageId());
            return;
        }

        UUID logId  = parseLogId(request.notificationLogId());
        UUID userId = parseUserId(request.userId());

        // При нулевом балансе — пропускаем, контроллер уже на паузе.
        // Используем userId (UUID), а не telegramId: для групповых чатов telegramId
        // содержит chatId группы (-987654...), а не id владельца контроллера.
        if (userId != null && tokenLedgerService.getBalance(userId) == 0) {
            log.debug("[DISPATCH] Пропуск: нулевой баланс userId={}", userId);
            if (logId != null) logService.markFailed(logId, "Нулевой баланс токенов");
            return;
        }

        // Guard against Kafka consumer replay (rebalance after dispatch but before offset commit):
        // if the log is already SENT, the Telegram message was already delivered — skip.
        if (logId != null && logService.isAlreadySent(logId)) {
            log.debug("[DISPATCH] Повтор — уже отправлено logId={}", logId);
            return;
        }

        try {
            Integer telegramMessageId = dispatchService.dispatch(request);
            if (logId != null) logService.markSent(logId, telegramMessageId);
            log.debug("[DISPATCH] Отправлено [logId={} channel={}]", logId, request.channel());

            // After successful Telegram delivery: populate dedup cache so subsequent
            // duplicates (same match, different event ID) edit this message instead.
            if (telegramMessageId != null && request.dedupKey() != null
                    && request.telegramId() != null) {
                titleDedupCache.store(request.dedupKey(), new TitleDedupEntry(
                        telegramMessageId,
                        request.telegramId(),
                        request.betKey(),
                        request.quickAddKey()));
            }

            // Списываем токен за успешное уведомление
            if (userId != null) {
                int cost = tokenLedgerService.getCost("NOTIFICATION_SENT");
                tokenLedgerService.tryDebit(userId, cost, TokenReasonCode.NOTIFICATION_SENT, null);
            }
        } catch (Exception e) {
            log.warn("[DISPATCH] Ошибка [logId={} channel={} userId={}]: {}",
                logId, request.channel(), userId, e.getMessage());
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

    private static UUID parseUserId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
