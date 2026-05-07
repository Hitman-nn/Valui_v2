package com.valui.notify.consumer;

import com.valui.common.kafka.AuditEntryMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.user.api.AuditLogPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Batch Kafka consumer for the {@code audit.log} topic.
 *
 * Drains up to 50 records per poll (configured via {@code max.poll.records}),
 * persists them in a single {@code saveAll} call via {@link AuditLogPortService},
 * then acknowledges the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {

    private final AuditLogPortService auditLogPort;

    @KafkaListener(
            topics = KafkaTopics.AUDIT_LOG,
            groupId = "valui-audit-group",
            containerFactory = "auditContainerFactory"
    )
    public void consume(List<AuditEntryMessage> messages, Acknowledgment ack) {
        if (messages.isEmpty()) {
            ack.acknowledge();
            return;
        }

        auditLogPort.persistBatch(messages);
        ack.acknowledge();

        log.debug("[AUDIT-CONSUMER] Persisted {} audit entries", messages.size());
    }
}
