package com.valui.user.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.entity.AuditFallbackEntity;
import com.valui.common.event.AuditEvent;
import com.valui.common.kafka.AuditEntryMessage;
import com.valui.common.kafka.KafkaTopics;
import com.valui.user.repository.AuditFallbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fire-and-forget audit publisher.
 *
 * Happy path: serialises the event and sends to {@code audit.log} asynchronously.
 * Fallback:   when Kafka is unavailable the whenComplete callback writes directly
 *             to {@code audit_fallback} so no event is silently dropped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditFallbackRepository fallbackRepository;
    private final ObjectMapper objectMapper;

    public void log(AuditEvent event) {
        AuditEntryMessage message = toMessage(event);
        String key = event.getUserId() != null ? event.getUserId().toString() : "anonymous";

        kafkaTemplate.send(KafkaTopics.AUDIT_LOG, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("[AUDIT] Kafka unavailable, falling back to DB: action={} userId={}",
                                event.getAction(), event.getUserId());
                        writeFallback(event);
                    }
                });
    }

    // ── private ───────────────────────────────────────────────────────────────

    private AuditEntryMessage toMessage(AuditEvent event) {
        String detailsJson = serializeDetails(event);
        return new AuditEntryMessage(
                event.getUserId() != null ? event.getUserId().toString() : null,
                event.getTelegramId(),
                event.getAction(),
                event.getEntityType(),
                event.getEntityId() != null ? event.getEntityId().toString() : null,
                detailsJson,
                event.getIpAddress(),
                event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now()
        );
    }

    private void writeFallback(AuditEvent event) {
        try {
            AuditFallbackEntity entity = AuditFallbackEntity.builder()
                    .userId(event.getUserId())
                    .telegramId(event.getTelegramId())
                    .action(event.getAction())
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId())
                    .details(serializeDetails(event))
                    .ipAddress(event.getIpAddress())
                    .occurredAt(toOffset(event.getOccurredAt()))
                    .build();
            fallbackRepository.save(entity);
            log.debug("[AUDIT-FALLBACK] Saved: action={} userId={}", event.getAction(), event.getUserId());
        } catch (Exception e) {
            log.error("[AUDIT-FALLBACK] Both Kafka and DB writes failed: action={}", event.getAction(), e);
        }
    }

    private String serializeDetails(AuditEvent event) {
        if (event.getDetails() == null || event.getDetails().isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(event.getDetails());
        } catch (JsonProcessingException e) {
            log.warn("[AUDIT] Failed to serialize details: {}", e.getMessage());
            return "{}";
        }
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : OffsetDateTime.now(ZoneOffset.UTC);
    }
}
