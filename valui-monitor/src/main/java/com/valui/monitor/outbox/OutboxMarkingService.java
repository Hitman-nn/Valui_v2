package com.valui.monitor.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Thin wrapper that lets Kafka send callbacks (running in non-transactional IO threads)
 * mark an outbox row as sent inside a fresh Spring-managed transaction.
 */
@Component
@RequiredArgsConstructor
class OutboxMarkingService {

    private final OutboxEventRepository outboxRepo;

    @Transactional
    public void markSent(Long outboxId) {
        outboxRepo.markSentAt(outboxId, OffsetDateTime.now());
    }
}
