package com.valui.notify.dedup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TitleDedupCacheService — unit tests")
class TitleDedupCacheServiceTest {

    @Mock StringRedisTemplate   redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ObjectMapper objectMapper = new ObjectMapper();

    TitleDedupCacheService service;

    static final long   CHAT_ID   = 111L;
    static final String BOOKMAKER = "FONBET";
    static final String URL       = "https://fonbet.ru/sports/soccer/tournament/12345/match/99";
    static final String TITLE     = "Spartak - CSKA";

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        service = new TitleDedupCacheService(redisTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "dedupTtlMinutes", 60);
    }

    // ── computeKey ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("same inputs produce the same key")
    void computeKey_sameInputs_sameKey() {
        String k1 = service.computeKey(CHAT_ID, BOOKMAKER, URL, TITLE);
        String k2 = service.computeKey(CHAT_ID, BOOKMAKER, URL, TITLE);

        assertThat(k1).isEqualTo(k2).startsWith("notif:title-dedup:");
    }

    @Test
    @DisplayName("different chatId produces different key")
    void computeKey_differentChatId_differentKey() {
        String k1 = service.computeKey(111L, BOOKMAKER, URL, TITLE);
        String k2 = service.computeKey(222L, BOOKMAKER, URL, TITLE);

        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    @DisplayName("URL with different last segment produces the same key (urlBase stripping)")
    void computeKey_differentEventIdSameBase_sameKey() {
        String url1 = "https://fonbet.ru/sports/soccer/match/111";
        String url2 = "https://fonbet.ru/sports/soccer/match/999";

        String k1 = service.computeKey(CHAT_ID, BOOKMAKER, url1, TITLE);
        String k2 = service.computeKey(CHAT_ID, BOOKMAKER, url2, TITLE);

        assertThat(k1).isEqualTo(k2);
    }

    @Test
    @DisplayName("null URL is handled without NPE")
    void computeKey_nullUrl_doesNotThrow() {
        assertThat(service.computeKey(CHAT_ID, BOOKMAKER, null, TITLE))
                .startsWith("notif:title-dedup:");
    }

    @Test
    @DisplayName("null title is normalised to empty string")
    void computeKey_nullTitle_doesNotThrow() {
        assertThat(service.computeKey(CHAT_ID, BOOKMAKER, URL, null))
                .startsWith("notif:title-dedup:");
    }

    @Test
    @DisplayName("title comparison is case-insensitive")
    void computeKey_titleCaseInsensitive_sameKey() {
        String k1 = service.computeKey(CHAT_ID, BOOKMAKER, URL, "Spartak - CSKA");
        String k2 = service.computeKey(CHAT_ID, BOOKMAKER, URL, "SPARTAK - CSKA");

        assertThat(k1).isEqualTo(k2);
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("find returns empty when Redis has no entry")
    void find_noEntry_returnsEmpty() {
        given(valueOps.get(anyString())).willReturn(null);

        assertThat(service.find("some-key")).isEmpty();
    }

    @Test
    @DisplayName("find returns deserialized entry when Redis has a matching value")
    void find_entryPresent_returnsEntry() throws Exception {
        TitleDedupEntry entry = new TitleDedupEntry(42, CHAT_ID, "bet-key", null);
        given(valueOps.get("some-key")).willReturn(objectMapper.writeValueAsString(entry));

        Optional<TitleDedupEntry> result = service.find("some-key");

        assertThat(result).isPresent();
        assertThat(result.get().telegramMessageId()).isEqualTo(42);
        assertThat(result.get().chatId()).isEqualTo(CHAT_ID);
        assertThat(result.get().betKey()).isEqualTo("bet-key");
    }

    @Test
    @DisplayName("find returns empty when Redis throws (Redis outage fallback)")
    void find_redisThrows_returnsEmpty() {
        given(valueOps.get(anyString())).willThrow(new RuntimeException("Redis connection refused"));

        assertThat(service.find("some-key")).isEmpty();
    }

    @Test
    @DisplayName("find returns empty on malformed JSON without propagating exception")
    void find_malformedJson_returnsEmpty() {
        given(valueOps.get(anyString())).willReturn("{invalid json}");

        assertThat(service.find("some-key")).isEmpty();
    }

    // ── store ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("store saves JSON to Redis with the configured TTL")
    void store_savesWithTtl() throws Exception {
        TitleDedupEntry entry = new TitleDedupEntry(99, CHAT_ID, null, "qa-key");

        service.store("my-key", entry);

        verify(valueOps).set(eq("my-key"), anyString(), eq(Duration.ofMinutes(60)));
    }

    @Test
    @DisplayName("store contains correct JSON fields")
    void store_jsonContainsTelegramMessageId() throws Exception {
        TitleDedupEntry entry = new TitleDedupEntry(77, CHAT_ID, "bk", null);

        service.store("k", entry);

        verify(valueOps).set(eq("k"),
                argThat(json -> json.contains("\"telegramMessageId\":77")),
                any());
    }
}
