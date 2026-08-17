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
 * <p>{@code markOpen}/{@code markClosed} are the atomic claim used by {@link ParserHealthChecker}
 * to decide whether IT needs to publish its own detection event (only when the canonical set —
 * this class's {@code KEY} — genuinely wasn't already flagged open/closed by anyone, fast
 * CB-path included — see its class javadoc for why that's the one detector allowed to skip
 * publishing when redundant).
 *
 * <p>{@link #claimOpen}/{@link #claimClosed} are a <em>separate</em>, per-{@code consumer} claim
 * for "have I personally already notified for this bookmaker's current open incident" — used by
 * {@code IncidentAlertListener} (admin) and {@code BookmakerIncidentNotifier} (users). These used
 * to share the exact same {@code markOpen}/{@code markClosed} claim as each other (and as
 * {@link ParserHealthChecker}'s own dedup above) — whichever of the three happened to call it
 * first "won" the single shared claim, so the other two silently saw "already claimed" and never
 * fired their own notification. {@code claimOpen}/{@code claimClosed} still update the same
 * canonical set as a side effect (so {@link #isOpen} stays correct for whoever notices an
 * incident first), but each consumer's own claim membership lives in its own Redis key and can no
 * longer be starved by another consumer's claim.
 */
@Component
@RequiredArgsConstructor
public class ParserIncidentStateStore {

    private static final String KEY = "parser:incidents:open";
    private static final String CLAIM_KEY_PREFIX = "parser:incidents:claimed:";

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

    /**
     * @return true if {@code consumer} had not already claimed this bookmaker's currently-open
     *         incident (i.e. this consumer should go ahead and notify). Independent of every
     *         other consumer's own claim.
     */
    public boolean claimOpen(String consumer, BookmakerType bookmaker) {
        redis.opsForSet().add(KEY, bookmaker.name());
        Long added = redis.opsForSet().add(claimKey(consumer), bookmaker.name());
        return added != null && added > 0;
    }

    /** @return true if {@code consumer} had claimed the (now-closing) incident and should notify. */
    public boolean claimClosed(String consumer, BookmakerType bookmaker) {
        redis.opsForSet().remove(KEY, bookmaker.name());
        Long removed = redis.opsForSet().remove(claimKey(consumer), bookmaker.name());
        return removed != null && removed > 0;
    }

    private static String claimKey(String consumer) {
        return CLAIM_KEY_PREFIX + consumer;
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
