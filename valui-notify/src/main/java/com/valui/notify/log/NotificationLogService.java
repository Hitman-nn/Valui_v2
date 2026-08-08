package com.valui.notify.log;

import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.UserPortService;
import com.valui.user.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authoritative DB status store for a notification's lifecycle, touched at every stage
 * (created → sent/failed) — and the source of the {@code logId} that every other log line in
 * the notify pipeline correlates on via MDC. Previously had zero logging of its own despite that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository repo;
    private final UserPortService userPort;
    private final DetectedEventPortService detectedEventPort;

    @Transactional
    public NotificationLogEntity createPending(UUID userId, UUID detectedEventId,
                                               NotificationChannel channel, Long chatId) {
        UserEntity user = userId != null ? userPort.getReferenceById(userId) : null;
        DetectedEventEntity event = detectedEventId != null
                ? detectedEventPort.getReferenceById(detectedEventId)
                : null;

        NotificationLogEntity saved = repo.save(NotificationLogEntity.builder()
                .user(user)
                .event(event)
                .channel(channel)
                .chatId(chatId)
                .status(NotificationStatus.PENDING)
                .build());
        log.debug("[NOTIF-LOG] Created PENDING logId={} userId={} channel={} chatId={}",
                saved.getId(), userId, channel, chatId);
        return saved;
    }

    @Transactional
    public void markSent(UUID logId, Integer telegramMessageId) {
        repo.findById(logId).ifPresentOrElse(entity -> {
            entity.setStatus(NotificationStatus.SENT);
            entity.setSentAt(OffsetDateTime.now());
            entity.setAttempts(entity.getAttempts() + 1);
            if (telegramMessageId != null) {
                entity.setTelegramMessageId(telegramMessageId.longValue());
            }
        }, () -> log.warn("[NOTIF-LOG] markSent: logId={} not found — status not persisted " +
                "(telegramMessageId={}). The Telegram message WAS sent; isAlreadySent() will keep " +
                "returning false for this logId, risking a duplicate re-send on replay.",
                logId, telegramMessageId));
    }

    /** Backward-compatible overload for non-Telegram channels and DLQ retries. */
    @Transactional
    public void markSent(UUID logId) {
        markSent(logId, null);
    }

    @Transactional(readOnly = true)
    public boolean isAlreadySent(UUID logId) {
        return repo.findById(logId)
                .map(entity -> entity.getStatus() == NotificationStatus.SENT)
                .orElse(false);
    }

    @Transactional
    public void markFailed(UUID logId, String errorMessage) {
        repo.findById(logId).ifPresentOrElse(entity -> {
            entity.setStatus(NotificationStatus.FAILED);
            entity.setErrorMessage(errorMessage != null && errorMessage.length() > 500
                    ? errorMessage.substring(0, 500)
                    : errorMessage);
            entity.setAttempts(entity.getAttempts() + 1);
        }, () -> log.warn("[NOTIF-LOG] markFailed: logId={} not found — error not persisted: {}",
                logId, errorMessage));
    }
}
