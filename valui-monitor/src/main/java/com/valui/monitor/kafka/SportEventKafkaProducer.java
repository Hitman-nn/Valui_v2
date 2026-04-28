package com.valui.monitor.kafka;

import com.valui.monitor.event.SportEventDetectedEvent;
import com.valui.monitor.outbox.OutboxSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the committed {@link SportEventDetectedEvent} to the Kafka outbox.
 *
 * Fires {@code AFTER_COMMIT} so the corresponding {@code OutboxEvent} row is
 * guaranteed to be visible before we attempt immediate delivery.
 * If the immediate send fails (Kafka unavailable), the row stays unsent and
 * {@link OutboxSenderService#scanAndSend()} retries it within 5 s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SportEventKafkaProducer {

    private final OutboxSenderService outboxSenderService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSportEventDetected(SportEventDetectedEvent event) {
        log.debug("AFTER_COMMIT: immediate outbox dispatch for externalEventId={}",
                event.externalEventId());
        outboxSenderService.publishImmediate(event.externalEventId());
    }
}
