package com.valui.notify.log;

import com.valui.notify.domain.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository repo;

    @Transactional
    public NotificationLogEntity createPending(UUID userId, UUID detectedEventId, NotificationChannel channel) {
        return repo.save(NotificationLogEntity.builder()
                .userId(userId)
                .eventId(detectedEventId)
                .channel(channel.name())
                .status("PENDING")
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @Transactional
    public void markSent(UUID logId) {
        repo.markSent(logId, OffsetDateTime.now());
    }

    @Transactional
    public void markFailed(UUID logId, String errorMessage) {
        String truncated = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;
        repo.markFailed(logId, truncated);
    }
}
