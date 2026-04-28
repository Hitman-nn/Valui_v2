package com.valui.app.schema;

import com.valui.common.kafka.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemaRegistrationService — unit tests")
class SchemaRegistrationServiceTest {

    @Mock RestTemplate restTemplate;
    @Mock SchemaRegistryProperties props;

    @InjectMocks SchemaRegistrationService service;

    private static final String SR_URL = "http://localhost:8081";

    // ── registration enabled ──────────────────────────────────────────────────

    @BeforeEach
    void setupProps() {
        given(props.isEnabled()).willReturn(true);
        given(props.getUrl()).willReturn(SR_URL);
    }

    @Test
    @DisplayName("registerSchemas registers all 4 topics when SR URL is set")
    void registerSchemas_registersAllTopics() {
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class), anyString()))
                .willReturn(Map.of("id", 1));

        service.registerSchemas();

        // 4 POST calls (one per topic schema)
        verify(restTemplate, times(4)).postForObject(
                contains("/subjects/"), any(), eq(Map.class), anyString());
        // 4 PUT calls (set BACKWARD compatibility)
        verify(restTemplate, times(4)).put(
                contains("/config/"), any(Map.class), anyString());
    }

    @Test
    @DisplayName("Subject names follow {topic}-value convention")
    void registerSchemas_subjectNamingConvention() {
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class), anyString()))
                .willReturn(Map.of("id", 1));

        service.registerSchemas();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate, times(4)).postForObject(
                anyString(), any(), eq(Map.class), subjectCaptor.capture());

        assertThat(subjectCaptor.getAllValues())
                .containsExactlyInAnyOrder(
                        KafkaTopics.SPORT_EVENTS_DETECTED + "-value",
                        KafkaTopics.USER_NOTIFICATIONS_PENDING + "-value",
                        KafkaTopics.AUDIT_LOG + "-value",
                        KafkaTopics.SUBSCRIPTION_EVENTS + "-value");
    }

    @Test
    @DisplayName("BACKWARD compatibility is set for every subject")
    void registerSchemas_setsBackwardCompatibility() {
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class), anyString()))
                .willReturn(Map.of("id", 1));

        service.registerSchemas();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> bodyCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(restTemplate, times(4)).put(anyString(), bodyCaptor.capture(), anyString());

        bodyCaptor.getAllValues().forEach(body ->
                assertThat(body).containsEntry("compatibility", "BACKWARD"));
    }

    @Test
    @DisplayName("Schema body contains 'schema' and 'schemaType' keys")
    void registerOne_requestBodyHasCorrectKeys() {
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class), anyString()))
                .willReturn(Map.of("id", 1));

        service.registerOne(KafkaTopics.SPORT_EVENTS_DETECTED, "SportEventDetected.avsc");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(anyString(), captor.capture(), eq(Map.class), anyString());

        Map<String, String> body = captor.getValue();
        assertThat(body).containsKey("schema");
        assertThat(body).containsEntry("schemaType", "AVRO");
        assertThat(body.get("schema")).contains("valui.events.v1");
    }

    // ── registration disabled ─────────────────────────────────────────────────

    @Test
    @DisplayName("registerSchemas skips all calls when SR URL is empty")
    void registerSchemas_skipsWhenUrlEmpty() {
        given(props.isEnabled()).willReturn(false);

        service.registerSchemas();

        verifyNoInteractions(restTemplate);
    }

    // ── resilience ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single subject failure does not stop other registrations")
    void registerSchemas_oneFailure_continuesOthers() {
        // First call throws, rest succeed
        given(restTemplate.postForObject(anyString(), any(), eq(Map.class), anyString()))
                .willThrow(new RuntimeException("SR unavailable"))
                .willReturn(Map.of("id", 1))
                .willReturn(Map.of("id", 2))
                .willReturn(Map.of("id", 3));

        service.registerSchemas();

        // All 4 attempted (no early exit after first failure)
        verify(restTemplate, times(4)).postForObject(
                anyString(), any(), eq(Map.class), anyString());
    }
}
