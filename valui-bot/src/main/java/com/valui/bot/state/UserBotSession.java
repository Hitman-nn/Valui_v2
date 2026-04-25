package com.valui.bot.state;

import lombok.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * FSM session for one Telegram user, serialized as JSON in Redis.
 * Key: {@code bot:session:{chatId}}, TTL: 30 minutes (reset on every access).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBotSession {

    private Long chatId;
    private BotState state;

    /** Temporary wizard context: chosen bookmaker, sport, url, etc. */
    @Builder.Default
    private Map<String, String> context = new HashMap<>();

    private Instant updatedAt;

    /** Context key constants */
    public static final String CTX_BOOKMAKER  = "bookmaker";
    public static final String CTX_SPORT      = "sport";
    public static final String CTX_URL        = "url";
    public static final String CTX_CONTROLLER_ID = "controllerId";
}
