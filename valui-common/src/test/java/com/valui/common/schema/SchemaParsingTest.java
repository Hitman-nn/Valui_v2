package com.valui.common.schema;

import org.apache.avro.JsonProperties;
import org.apache.avro.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Validates that all .avsc schema files are syntactically correct Avro schemas
 * and match the expected contract (namespace, field presence, types).
 *
 * Tests use classpath resources (avsc files are included via build config in valui-common/pom.xml).
 * Fast unit tests — no external dependencies.
 */
@DisplayName("Avro schema contract tests")
class SchemaParsingTest {

    // ── all schemas parse ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "SportEventDetected.avsc",
            "UserNotificationRequest.avsc",
            "AuditEntry.avsc",
            "SubscriptionChangedEvent.avsc"
    })
    @DisplayName("Schema parses without errors")
    void schema_parsesWithoutErrors(String filename) {
        assertThatNoException().isThrownBy(() -> {
            Schema schema = load(filename);
            assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
            assertThat(schema.getNamespace()).isEqualTo("valui.events.v1");
        });
    }

    // ── SportEventDetected ────────────────────────────────────────────────────

    @Test
    @DisplayName("SportEventDetected — all required fields present with correct types")
    void sportEventDetected_fields() throws IOException {
        Schema schema = load("SportEventDetected.avsc");

        assertRequiredString(schema, "event_id");
        assertRequiredString(schema, "controller_id");
        assertRequiredString(schema, "user_id");
        assertRequiredLong(schema, "telegram_id");
        assertRequiredString(schema, "bookmaker");
        assertRequiredString(schema, "title");
        assertRequiredString(schema, "url");

        Schema.Field detectedAt = schema.getField("detected_at");
        assertThat(detectedAt).isNotNull();
        assertThat(detectedAt.schema().getType()).isEqualTo(Schema.Type.LONG);
        assertThat(detectedAt.schema().getProp("logicalType")).isEqualTo("timestamp-millis");
    }

    // ── UserNotificationRequest ───────────────────────────────────────────────

    @Test
    @DisplayName("UserNotificationRequest — all required fields present")
    void userNotificationRequest_fields() throws IOException {
        Schema schema = load("UserNotificationRequest.avsc");

        assertRequiredString(schema, "notification_id");
        assertRequiredString(schema, "user_id");
        assertRequiredLong(schema, "telegram_id");
        assertRequiredString(schema, "channel");
        assertRequiredString(schema, "message_text");

        // event_id is nullable union with null default
        Schema.Field eventId = schema.getField("event_id");
        assertThat(eventId).isNotNull();
        assertThat(eventId.schema().getType()).isEqualTo(Schema.Type.UNION);
        // Avro represents JSON null default as JsonProperties.NULL_VALUE, not Java null
        assertThat(eventId.defaultVal()).isSameAs(JsonProperties.NULL_VALUE);
    }

    // ── AuditEntry ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AuditEntry — all required fields present, optional fields nullable")
    void auditEntry_fields() throws IOException {
        Schema schema = load("AuditEntry.avsc");

        assertRequiredString(schema, "audit_id");
        assertRequiredString(schema, "user_id");
        assertRequiredLong(schema, "telegram_id");
        assertRequiredString(schema, "action");
        assertRequiredString(schema, "details_json");

        assertNullableUnion(schema, "entity_type");
        assertNullableUnion(schema, "entity_id");

        Schema.Field occurredAt = schema.getField("occurred_at");
        assertThat(occurredAt).isNotNull();
        assertThat(occurredAt.schema().getProp("logicalType")).isEqualTo("timestamp-millis");
    }

    // ── SubscriptionChangedEvent ──────────────────────────────────────────────

    @Test
    @DisplayName("SubscriptionChangedEvent — fields present, old_plan is nullable")
    void subscriptionChangedEvent_fields() throws IOException {
        Schema schema = load("SubscriptionChangedEvent.avsc");

        assertRequiredString(schema, "user_id");
        assertRequiredString(schema, "new_plan");
        assertNullableUnion(schema, "old_plan");
    }

    // ── backward compatibility: new optional fields can be added ─────────────

    @Test
    @DisplayName("All nullable fields have null default — required for BACKWARD compatibility")
    void nullableFields_haveNullDefault() throws IOException {
        for (String filename : new String[]{
                "SportEventDetected.avsc", "UserNotificationRequest.avsc",
                "AuditEntry.avsc", "SubscriptionChangedEvent.avsc"}) {
            Schema schema = load(filename);
            for (Schema.Field field : schema.getFields()) {
                if (field.schema().getType() == Schema.Type.UNION) {
                    // Avro uses JsonProperties.NULL_VALUE (not Java null) to represent "default": null
                    assertThat(field.defaultVal())
                            .as("Union field '%s' in %s must have \"default\": null for BACKWARD compat",
                                    field.name(), filename)
                            .isSameAs(JsonProperties.NULL_VALUE);
                }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Schema load(String filename) throws IOException {
        try (InputStream is = SchemaParsingTest.class.getResourceAsStream("/avro/" + filename)) {
            assertThat(is).as("Schema file not found on classpath: avro/" + filename).isNotNull();
            return new Schema.Parser().parse(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void assertRequiredString(Schema schema, String fieldName) {
        Schema.Field field = schema.getField(fieldName);
        assertThat(field).as("Field '%s' missing", fieldName).isNotNull();
        assertThat(field.schema().getType())
                .as("Field '%s' should be STRING", fieldName)
                .isEqualTo(Schema.Type.STRING);
    }

    private static void assertRequiredLong(Schema schema, String fieldName) {
        Schema.Field field = schema.getField(fieldName);
        assertThat(field).as("Field '%s' missing", fieldName).isNotNull();
        assertThat(field.schema().getType())
                .as("Field '%s' should be LONG", fieldName)
                .isEqualTo(Schema.Type.LONG);
    }

    private static void assertNullableUnion(Schema schema, String fieldName) {
        Schema.Field field = schema.getField(fieldName);
        assertThat(field).as("Field '%s' missing", fieldName).isNotNull();
        assertThat(field.schema().getType())
                .as("Field '%s' should be UNION (nullable)", fieldName)
                .isEqualTo(Schema.Type.UNION);
    }
}
