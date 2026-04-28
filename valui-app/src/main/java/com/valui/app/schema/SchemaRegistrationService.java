package com.valui.app.schema;

import com.valui.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Registers Avro schemas with Confluent Schema Registry at application startup.
 *
 * Uses the Schema Registry REST API directly (no Confluent client library needed —
 * reduces dependency footprint and avoids Kafka client version conflicts).
 *
 * Behaviour:
 *   - Skipped silently if {@code kafka.schema-registry.url} is empty (local dev without SR).
 *   - Idempotent: re-registration of an identical schema is a no-op in SR.
 *   - Compatibility mode BACKWARD is set per-subject (new consumers read old messages).
 *   - Individual subject failures are logged as WARN and do not abort startup.
 *
 * Subject naming convention: {@code {topic-name}-value}
 * Schema files: classpath:avro/*.avsc (included from valui-common/src/main/avro)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SchemaRegistryProperties.class)
public class SchemaRegistrationService {

    /** topic → avsc filename */
    private static final Map<String, String> TOPIC_TO_SCHEMA = Map.of(
            KafkaTopics.SPORT_EVENTS_DETECTED,       "SportEventDetected.avsc",
            KafkaTopics.USER_NOTIFICATIONS_PENDING,  "UserNotificationRequest.avsc",
            KafkaTopics.AUDIT_LOG,                   "AuditEntry.avsc",
            KafkaTopics.SUBSCRIPTION_EVENTS,         "SubscriptionChangedEvent.avsc"
    );

    private final SchemaRegistryProperties props;
    private final RestTemplate restTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void registerSchemas() {
        if (!props.isEnabled()) {
            log.info("[SCHEMA-REGISTRY] URL not configured — skipping schema registration");
            return;
        }
        log.info("[SCHEMA-REGISTRY] Registering {} schemas at {}", TOPIC_TO_SCHEMA.size(), props.getUrl());
        TOPIC_TO_SCHEMA.forEach((topic, filename) -> registerOne(topic, filename));
    }

    // ── package-private: used by tests ────────────────────────────────────────

    void registerOne(String topic, String filename) {
        String subject = topic + "-value";
        try {
            String schemaJson = loadSchemaJson(filename);
            validateParseable(schemaJson, filename);

            int id = postSchema(subject, schemaJson);
            setCompatibility(subject, "BACKWARD");
            log.info("[SCHEMA-REGISTRY] Registered subject={} schemaId={}", subject, id);
        } catch (Exception e) {
            log.warn("[SCHEMA-REGISTRY] Failed to register subject={}: {}", subject, e.getMessage());
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private int postSchema(String subject, String schemaJson) {
        String endpoint = props.getUrl() + "/subjects/{subject}/versions";
        Map<String, String> body = Map.of("schema", schemaJson, "schemaType", "AVRO");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(endpoint, body, Map.class, subject);
            return response != null && response.containsKey("id")
                    ? (int) response.get("id")
                    : -1;
        } catch (HttpClientErrorException.Conflict e) {
            // 409 = schema already registered with this ID — idempotent
            log.debug("[SCHEMA-REGISTRY] Subject {} already registered (409)", subject);
            return -1;
        }
    }

    private void setCompatibility(String subject, String level) {
        String endpoint = props.getUrl() + "/config/{subject}";
        restTemplate.put(endpoint, Map.of("compatibility", level), subject);
    }

    private static String loadSchemaJson(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("avro/" + filename);
        if (!resource.exists()) {
            throw new IllegalStateException("Schema file not found on classpath: avro/" + filename);
        }
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static void validateParseable(String json, String filename) {
        new Schema.Parser().parse(json); // throws SchemaParseException on invalid schema
    }
}
