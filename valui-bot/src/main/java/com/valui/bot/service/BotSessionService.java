package com.valui.bot.service;

import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manages bot conversation state persisted in Redis.
 * TTL is refreshed to 30 minutes on every read or write.
 */
@Slf4j
@Service
public class BotSessionService {

    static final Duration SESSION_TTL = Duration.ofMinutes(30);
    static final String KEY_PREFIX = "bot:session:";

    private final RedisTemplate<String, UserBotSession> redisTemplate;

    public BotSessionService(
            @Qualifier("botSessionRedisTemplate") RedisTemplate<String, UserBotSession> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns the session for the given chatId.
     * Creates a new IDLE session if none exists (not persisted until a write occurs).
     * Always refreshes TTL.
     */
    public UserBotSession getSession(Long chatId) {
        String key = key(chatId);
        UserBotSession session = redisTemplate.opsForValue().get(key);

        if (session == null) {
            log.debug("No session for chatId={} — returning transient IDLE", chatId);
            return UserBotSession.builder()
                .chatId(chatId)
                .state(BotState.IDLE)
                .context(new HashMap<>())
                .updatedAt(Instant.now())
                .build();
        }

        redisTemplate.expire(key, SESSION_TTL);
        return session;
    }

    /** Transitions the session to {@code state}, preserving existing context. */
    public void setState(Long chatId, BotState state) {
        UserBotSession session = getSession(chatId);
        session.setState(state);
        session.setUpdatedAt(Instant.now());
        save(chatId, session);
        log.debug("State set: chatId={} state={}", chatId, state);
    }

    /**
     * Transitions to {@code state} and REPLACES the context with {@code ctx}.
     * Use this when starting a new wizard step that requires fresh context.
     */
    public void setStateWithContext(Long chatId, BotState state, Map<String, String> ctx) {
        UserBotSession session = getSession(chatId);
        session.setState(state);
        session.setContext(ctx != null ? new HashMap<>(ctx) : new HashMap<>());
        session.setUpdatedAt(Instant.now());
        save(chatId, session);
        log.debug("State+context set: chatId={} state={} ctx={}", chatId, state, ctx);
    }

    /** Returns a context value, or {@link Optional#empty()} if not present. */
    public Optional<String> getContext(Long chatId, String key) {
        Map<String, String> ctx = getSession(chatId).getContext();
        return ctx != null ? Optional.ofNullable(ctx.get(key)) : Optional.empty();
    }

    /**
     * Resets the session to IDLE with empty context.
     * Does NOT delete the Redis key — retains the key with fresh TTL.
     */
    public void clearSession(Long chatId) {
        UserBotSession fresh = UserBotSession.builder()
            .chatId(chatId)
            .state(BotState.IDLE)
            .context(new HashMap<>())
            .updatedAt(Instant.now())
            .build();
        save(chatId, fresh);
        log.debug("Session cleared: chatId={}", chatId);
    }

    /** Removes the session key entirely from Redis (hard delete). */
    public void deleteSession(Long chatId) {
        redisTemplate.delete(key(chatId));
        log.debug("Session deleted: chatId={}", chatId);
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void save(Long chatId, UserBotSession session) {
        redisTemplate.opsForValue().set(key(chatId), session, SESSION_TTL);
    }

    private String key(Long chatId) {
        return KEY_PREFIX + chatId;
    }
}
