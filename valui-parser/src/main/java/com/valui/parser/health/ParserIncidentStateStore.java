package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Tracks which bookmakers currently have an open (unresolved) unavailability incident — in
 * Redis, not JVM memory.
 *
 * <p>A freshly created {@link io.github.resilience4j.circuitbreaker.CircuitBreaker} always boots
 * {@code CLOSED} and never emits a {@code HALF_OPEN_TO_CLOSED} transition for that boot — so
 * anything gating a "recovered" notification purely on that transition edge silently loses the
 * signal if the process restarts while an incident is still open (state was {@code OPEN} or
 * {@code HALF_OPEN} at shutdown). This store is the thing that survives the restart: whichever
 * detector notices the outage first — the fast CB-transition path in
 * {@code BookmakerIncidentNotifier}, or {@link ParserHealthChecker}'s slow active-probe backstop
 * — claims the incident here via {@link #markOpen}, and only {@link ParserHealthChecker}'s
 * post-restart probing (the one detector that re-verifies reality rather than trusting a
 * freshly-reset CB) can close it via {@link #markClosed}, regardless of which one opened it.
 *
 * <p>{@code markOpen}/{@code markClosed} are the atomic claim: only the caller that actually
 * changes membership gets {@code true}, so callers use the return value as "is this genuinely a
 * new transition" rather than tracking their own dedup state.
 */
@Component
@RequiredArgsConstructor
public class ParserIncidentStateStore {

    private static final String KEY = "parser:incidents:open";

    private final StringRedisTemplate redis;

    /** @return true if this call is the one that opened the incident (was not already open). */
    public boolean markOpen(BookmakerType bookmaker) {
        Long added = redis.opsForSet().add(KEY, bookmaker.name());
        return added != null && added > 0;
    }

    /** @return true if this call is the one that closed the incident (was open). */
    public boolean markClosed(BookmakerType bookmaker) {
        Long removed = redis.opsForSet().remove(KEY, bookmaker.name());
        return removed != null && removed > 0;
    }

    public boolean isOpen(BookmakerType bookmaker) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY, bookmaker.name()));
    }

    public Set<BookmakerType> getOpen() {
        Set<String> raw = redis.opsForSet().members(KEY);
        if (raw == null || raw.isEmpty()) return EnumSet.noneOf(BookmakerType.class);
        Set<BookmakerType> result = EnumSet.noneOf(BookmakerType.class);
        for (String name : raw) {
            try {
                result.add(BookmakerType.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // stale/foreign entry — ignore rather than fail the whole read
            }
        }
        return result;
    }
}
