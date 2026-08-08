package com.valui.bot.handler.callback.betting;

import com.valui.bot.handler.BotUpdateContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves which chat's betting journal a given update should operate on.
 *
 * In a group chat that's unambiguous — always the group itself. From DM there's no such
 * built-in scope (the physical chat is the user's own personal chat, which never has any bet
 * accounts), so the user first picks one of their linked groups (see {@link BetChatPickerCallback}).
 *
 * <p>The choice is stored in its own small Redis entry (keyed by {@code fromId}), deliberately
 * <b>not</b> in {@code UserBotSession}'s wizard context: menu navigation inside the betting flow
 * calls {@code sessionService.clearSession(fromId)} constantly (every "← В меню" / "✕ Отмена"
 * resets wizard state on purpose) — piggybacking on that context would mean the chat selection
 * gets wiped on every such tap, forcing the user to re-pick constantly instead of once per
 * {@code /bet} entry as intended. Cleared explicitly by {@link com.valui.bot.handler.command.BetCommandHandler}
 * on every fresh entry into the betting section (so a new /bet always re-prompts, per design),
 * and left alone by everything else in between.
 *
 * <p>Every betting handler that calls into {@code BettingService}/{@code BetAccountService}
 * must use {@link #resolve} instead of {@code ctx.chatId()} directly for that purpose —
 * {@code ctx.chatId()} stays correct for message routing (where to reply), since that's always
 * the physical chat the conversation is happening in, group or DM.
 */
@Component
@RequiredArgsConstructor
public class BettingChatResolver {

    private static final String KEY_PREFIX = "bot:betting-chat:";
    // Generous, sliding-window TTL — refreshed on every resolve() so an actively-used DM
    // session never expires mid-flow, but an abandoned one eventually cleans itself up.
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    /**
     * @return the effective chatId to scope bet accounts/persons/bets to.
     * @throws IllegalStateException in DM with no chat selected yet — should never happen for
     *         handlers reached via the normal flow (the picker always runs first and stores the
     *         selection before any deeper callback becomes reachable); if it does fire, it means
     *         a stale button was tapped after the 2h TTL expired.
     */
    public long resolve(BotUpdateContext ctx) {
        if (ctx.isGroupChat()) return ctx.chatId();
        String key = key(ctx.fromId());
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw new IllegalStateException(
                    "No betting chat selected in DM session for fromId=" + ctx.fromId());
        }
        redisTemplate.expire(key, TTL);
        return Long.parseLong(stored);
    }

    /** True once a DM session has a chat selected (or always true in a group). Lets entry-point
     * handlers decide whether to render the menu directly or show the picker first. */
    public boolean isResolved(BotUpdateContext ctx) {
        return ctx.isGroupChat() || Boolean.TRUE.equals(redisTemplate.hasKey(key(ctx.fromId())));
    }

    /**
     * Best-effort variant of {@link #resolve} for read-only/browse screens that have a sane
     * per-user fallback instead of a hard requirement (e.g. the controllers list, which shows
     * the caller's own controllers when no group is selected). Never throws:
     *
     * <ul>
     *   <li>group chat → {@code ctx.chatId()}, same as {@link #resolve};</li>
     *   <li>DM with a selection → the resolved chat, same as {@link #resolve};</li>
     *   <li>DM with no selection → {@code ctx.chatId()} (the user's own personal chat) instead
     *       of throwing — callers combine this with {@link #isResolved} to pick which data set
     *       ("my own" vs. "this group's") that chatId should be used to fetch.</li>
     * </ul>
     */
    public long resolveOrPhysical(BotUpdateContext ctx) {
        return isResolved(ctx) ? resolve(ctx) : ctx.chatId();
    }

    /** Records the user's chat selection for the rest of this DM betting session. */
    public void select(BotUpdateContext ctx, long chatId) {
        redisTemplate.opsForValue().set(key(ctx.fromId()), String.valueOf(chatId), TTL);
    }

    /** Forgets the remembered selection — called on every fresh /bet entry so a new session
     * always re-prompts instead of silently reusing a stale choice. */
    public void clear(long fromId) {
        redisTemplate.delete(key(fromId));
    }

    private static String key(long fromId) {
        return KEY_PREFIX + fromId;
    }
}
