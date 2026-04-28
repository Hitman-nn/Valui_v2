package com.valui.notify.consumer;

import com.valui.common.entity.AuditLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.kafka.AuditEntryMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Batch Kafka consumer for the {@code audit.log} topic.
 *
 * Drains up to 50 records per poll (configured via {@code max.poll.records}),
 * persists them in a single {@code saveAll} call, then acknowledges the batch.
 * On processing failure the error handler logs and skips (audit loss is acceptable
 * versus a poison-pill blocking the partition indefinitely).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

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

        List<AuditLogEntity> entries = messages.stream()
                .map(this::toEntity)
                .toList();

        auditLogRepository.saveAll(entries);
        ack.acknowledge();

        log.debug("[AUDIT-CONSUMER] Persisted {} audit entries", entries.size());
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private AuditLogEntity toEntity(AuditEntryMessage msg) {
        UUID userId = parseUuid(msg.userId());
        UserEntity userRef = userId != null ? userRepository.getReferenceById(userId) : null;

        UUID entityId = parseUuid(msg.entityId());

        // createdAt is set by AuditEntityListener on prePersist
        return AuditLogEntity.builder()
                .user(userRef)
                .action(msg.action())
                .entityType(msg.entityType())
                .entityId(entityId)
                .details(msg.details())
                .ipAddress(msg.ipAddress())
                .build();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
