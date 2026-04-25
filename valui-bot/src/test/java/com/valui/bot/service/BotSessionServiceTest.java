package com.valui.bot.service;

import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.BDDMockito.*;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)  // setUp stubs opsForValue — not needed by all tests
@DisplayName("BotSessionService — unit tests")
class BotSessionServiceTest {

    @Mock private RedisTemplate<String, UserBotSession> redisTemplate;
    @Mock private ValueOperations<String, UserBotSession> valueOps;

    private BotSessionService service;

    private static final Long   CHAT_ID = 42L;
    private static final String KEY     = "bot:session:42";
    private static final Duration TTL  = Duration.ofMinutes(30);

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        service = new BotSessionService(redisTemplate);
    }

    // ─── getSession ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSession: no existing session → returns transient IDLE (not persisted)")
    void getSession_noSession_returnsIdleWithoutSaving() {
        given(valueOps.get(KEY)).willReturn(null);

        UserBotSession session = service.getSession(CHAT_ID);

        assertThat(session.getChatId()).isEqualTo(CHAT_ID);
        assertThat(session.getState()).isEqualTo(BotState.IDLE);
        assertThat(session.getContext()).isNotNull().isEmpty();
        assertThat(session.getUpdatedAt()).isNotNull();
        // New session is transient — NOT saved to Redis
        then(valueOps).should(never()).set(any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("getSession: existing session → returns it and refreshes TTL")
    void getSession_existingSession_returnsAndRefreshTtl() {
        UserBotSession stored = existingSession(BotState.SELECTING_BOOKMAKER);
        given(valueOps.get(KEY)).willReturn(stored);

        UserBotSession result = service.getSession(CHAT_ID);

        assertThat(result.getState()).isEqualTo(BotState.SELECTING_BOOKMAKER);
        // TTL must be refreshed on every read
        then(redisTemplate).should().expire(KEY, TTL);
    }

    // ─── setState ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "setState → {0}")
    @EnumSource(BotState.class)
    @DisplayName("setState: persists correct state for all enum values")
    void setState_allValues_persistsCorrectly(BotState targetState) {
        given(valueOps.get(KEY)).willReturn(null);

        service.setState(CHAT_ID, targetState);

        ArgumentCaptor<UserBotSession> captor = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should().set(eq(KEY), captor.capture(), eq(TTL));
        assertThat(captor.getValue().getState()).isEqualTo(targetState);
        assertThat(captor.getValue().getChatId()).isEqualTo(CHAT_ID);
    }

    @Test
    @DisplayName("setState: preserves existing context when transitioning state")
    void setState_preservesExistingContext() {
        Map<String, String> ctx = Map.of(UserBotSession.CTX_BOOKMAKER, "XBET");
        UserBotSession stored = existingSession(BotState.SELECTING_SPORT);
        stored.setContext(new HashMap<>(ctx));
        given(valueOps.get(KEY)).willReturn(stored);

        service.setState(CHAT_ID, BotState.WAITING_FILTER_RULE);

        ArgumentCaptor<UserBotSession> captor = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should().set(eq(KEY), captor.capture(), eq(TTL));
        assertThat(captor.getValue().getState()).isEqualTo(BotState.WAITING_FILTER_RULE);
        assertThat(captor.getValue().getContext()).containsEntry(UserBotSession.CTX_BOOKMAKER, "XBET");
    }

    // ─── setStateWithContext ─────────────────────────────────────────────────

    @Test
    @DisplayName("setStateWithContext: replaces context and sets new state")
    void setStateWithContext_replacesContextAndSetsState() {
        given(valueOps.get(KEY)).willReturn(
            existingSession(BotState.IDLE, Map.of("old", "value"))
        );

        Map<String, String> newCtx = Map.of(UserBotSession.CTX_BOOKMAKER, "FONBET",
                                             UserBotSession.CTX_SPORT, "football");
        service.setStateWithContext(CHAT_ID, BotState.SELECTING_SPORT, newCtx);

        ArgumentCaptor<UserBotSession> captor = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should().set(eq(KEY), captor.capture(), eq(TTL));
        UserBotSession saved = captor.getValue();
        assertThat(saved.getState()).isEqualTo(BotState.SELECTING_SPORT);
        assertThat(saved.getContext()).containsEntry(UserBotSession.CTX_BOOKMAKER, "FONBET");
        assertThat(saved.getContext()).doesNotContainKey("old"); // old context replaced
    }

    @Test
    @DisplayName("setStateWithContext: null ctx → stored as empty map")
    void setStateWithContext_nullCtx_storesEmptyContext() {
        given(valueOps.get(KEY)).willReturn(null);

        service.setStateWithContext(CHAT_ID, BotState.WAITING_CONTROLLER_URL, null);

        ArgumentCaptor<UserBotSession> captor = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should().set(eq(KEY), captor.capture(), eq(TTL));
        assertThat(captor.getValue().getContext()).isNotNull().isEmpty();
    }

    // ─── getContext ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getContext: key present → returns value")
    void getContext_keyPresent_returnsValue() {
        UserBotSession session = existingSession(BotState.SELECTING_SPORT,
            Map.of(UserBotSession.CTX_SPORT, "tennis"));
        given(valueOps.get(KEY)).willReturn(session);

        Optional<String> result = service.getContext(CHAT_ID, UserBotSession.CTX_SPORT);

        assertThat(result).isPresent().contains("tennis");
    }

    @Test
    @DisplayName("getContext: key absent → returns empty")
    void getContext_keyAbsent_returnsEmpty() {
        given(valueOps.get(KEY)).willReturn(null);

        Optional<String> result = service.getContext(CHAT_ID, "missing-key");

        assertThat(result).isEmpty();
    }

    // ─── clearSession ────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearSession: resets to IDLE with empty context (soft reset, no prior read)")
    void clearSession_resetsToIdleWithEmptyContext() {
        // clearSession creates a fresh session directly — no Redis read needed
        service.clearSession(CHAT_ID);

        ArgumentCaptor<UserBotSession> captor = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should().set(eq(KEY), captor.capture(), eq(TTL));
        UserBotSession cleared = captor.getValue();
        assertThat(cleared.getState()).isEqualTo(BotState.IDLE);
        assertThat(cleared.getContext()).isEmpty();
    }

    // ─── deleteSession ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteSession: removes key from Redis entirely")
    void deleteSession_removesRedisKey() {
        service.deleteSession(CHAT_ID);

        then(redisTemplate).should().delete(KEY);
    }

    // ─── TTL refresh ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("TTL: every write uses 30-minute expiry")
    void ttl_everyWriteUses30MinExpiry() {
        given(valueOps.get(KEY)).willReturn(null);

        service.setState(CHAT_ID, BotState.WAITING_FILTER_RULE);

        then(valueOps).should().set(eq(KEY), any(UserBotSession.class), eq(TTL));
    }

    @Test
    @DisplayName("TTL: getSession on existing session calls expire")
    void ttl_getOnExistingSessionCallsExpire() {
        given(valueOps.get(KEY)).willReturn(existingSession(BotState.IDLE));

        service.getSession(CHAT_ID);

        then(redisTemplate).should().expire(KEY, TTL);
    }

    // ─── state transition flow ────────────────────────────────────────────────

    @Test
    @DisplayName("FSM: IDLE → SELECTING_BOOKMAKER → SELECTING_SPORT → IDLE cycle")
    void stateMachine_fullWizardCycle() {
        // Step 1: Start — no session
        given(valueOps.get(KEY)).willReturn(null);
        service.setStateWithContext(CHAT_ID, BotState.SELECTING_BOOKMAKER,
            Map.of(UserBotSession.CTX_BOOKMAKER, "XBET"));

        ArgumentCaptor<UserBotSession> c1 = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should(times(1)).set(eq(KEY), c1.capture(), eq(TTL));
        UserBotSession afterStep1 = c1.getValue();
        assertThat(afterStep1.getState()).isEqualTo(BotState.SELECTING_BOOKMAKER);

        // Step 2: Select sport — session exists from step 1
        given(valueOps.get(KEY)).willReturn(afterStep1);
        service.setStateWithContext(CHAT_ID, BotState.SELECTING_SPORT,
            Map.of(UserBotSession.CTX_BOOKMAKER, "XBET", UserBotSession.CTX_SPORT, "football"));

        ArgumentCaptor<UserBotSession> c2 = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should(times(2)).set(eq(KEY), c2.capture(), eq(TTL));
        UserBotSession afterStep2 = c2.getValue();
        assertThat(afterStep2.getState()).isEqualTo(BotState.SELECTING_SPORT);
        assertThat(afterStep2.getContext()).containsEntry(UserBotSession.CTX_SPORT, "football");

        // Step 3: Reset to IDLE — clearSession does NOT read from Redis
        service.clearSession(CHAT_ID);

        ArgumentCaptor<UserBotSession> c3 = ArgumentCaptor.forClass(UserBotSession.class);
        then(valueOps).should(times(3)).set(eq(KEY), c3.capture(), eq(TTL));
        assertThat(c3.getValue().getState()).isEqualTo(BotState.IDLE);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static UserBotSession existingSession(BotState state) {
        return existingSession(state, new HashMap<>());
    }

    private static UserBotSession existingSession(BotState state, Map<String, String> ctx) {
        return UserBotSession.builder()
            .chatId(CHAT_ID)
            .state(state)
            .context(new HashMap<>(ctx))
            .updatedAt(Instant.now())
            .build();
    }
}
