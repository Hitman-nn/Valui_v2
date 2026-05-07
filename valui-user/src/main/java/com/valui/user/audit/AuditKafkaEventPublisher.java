package com.valui.user.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the audit Kafka message only after the DB transaction has committed.
 * fallbackExecution = true: fires even when there is no active transaction
 * (bot context, scheduled tasks, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditKafkaEventPublisher {

    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEvent(AuditApplicationEvent event) {
        try {
            auditService.log(event.getAuditEvent());
        } catch (Exception e) {
            log.error("[AUDIT] Failed to publish after commit: action={}", event.getAuditEvent().getAction(), e);
        }
    }
}
