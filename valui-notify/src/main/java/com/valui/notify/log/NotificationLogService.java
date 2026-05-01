package com.valui.notify.log;

import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.DetectedEventRepository;
import com.valui.user.repository.NotificationLogRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository repo;
    private final UserRepository userRepo;
    private final DetectedEventRepository detectedEventRepo;

    @Transactional
    public NotificationLogEntity createPending(UUID userId, UUID detectedEventId,
                                               NotificationChannel channel, Long chatId) {
        UserEntity user = userId != null ? userRepo.getReferenceById(userId) : null;
        DetectedEventEntity event = detectedEventId != null
                ? detectedEventRepo.getReferenceById(detectedEventId)
                : null;

        return repo.save(NotificationLogEntity.builder()
                .user(user)
                .event(event)
                .channel(channel)
                .chatId(chatId)
                .status(NotificationStatus.PENDING)
                .build());
    }

    @Transactional
    public void markSent(UUID logId) {
        repo.findById(logId).ifPresent(log -> {
            log.setStatus(NotificationStatus.SENT);
            log.setSentAt(OffsetDateTime.now());
            log.setAttempts(log.getAttempts() + 1);
        });
    }

    @Transactional(readOnly = true)
    public boolean isAlreadySent(UUID logId) {
        return repo.findById(logId)
                .map(log -> log.getStatus() == NotificationStatus.SENT)
                .orElse(false);
    }

    @Transactional
    public void markFailed(UUID logId, String errorMessage) {
        repo.findById(logId).ifPresent(log -> {
            log.setStatus(NotificationStatus.FAILED);
            log.setErrorMessage(errorMessage != null && errorMessage.length() > 500
                    ? errorMessage.substring(0, 500)
                    : errorMessage);
            log.setAttempts(log.getAttempts() + 1);
        });
    }
}
