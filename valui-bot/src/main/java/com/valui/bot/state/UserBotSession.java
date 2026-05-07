package com.valui.bot.state;

import lombok.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * FSM session for one Telegram user, serialized as JSON in Redis.
 * Key: {@code bot:session:{fromId}} (user's personal Telegram ID), TTL: 30 minutes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBotSession {

    private Long chatId;
    private BotState state;

    @Builder.Default
    private Map<String, String> context = new HashMap<>();

    private Instant updatedAt;

    // ── General wizard keys ───────────────────────────────────────────────────
    public static final String CTX_BOOKMAKER              = "bookmaker";
    public static final String CTX_SPORT                  = "sport";
    public static final String CTX_URL                    = "url";
    public static final String CTX_CONTROLLER_ID          = "controllerId";
    public static final String CTX_SPORT_ID               = "sportId";
    public static final String CTX_SPORT_NAME             = "sportName";
    public static final String CTX_SPORT_ALIAS            = "sportAlias";
    public static final String CTX_CONTROLLER_TYPE        = "controllerType";
    public static final String CTX_TOURNAMENT_ID          = "tournamentId";
    public static final String CTX_TOURNAMENT_TITLE       = "tournamentTitle";
    public static final String CTX_TOURNAMENT_URL         = "tournamentUrl";
    public static final String CTX_FILTER                 = "filter";
    public static final String CTX_FILTER_MODE            = "filterMode";
    public static final String CTX_EDIT_FILTER_ID         = "editFilterId";
    public static final String CTX_EDIT_CONTROLLER_ID     = "editControllerId";
    public static final String CTX_WIZARD_MSG_ID          = "wizardMsgId";
    public static final String CTX_SEARCH_TARGET          = "searchTarget";
    public static final String CTX_CACHED_SPORTS_JSON     = "cachedSportsJson";
    public static final String CTX_CACHED_TOURNAMENTS_JSON = "cachedTournamentsJson";

    // ── Betting wizard keys ───────────────────────────────────────────────────
    public static final String CTX_BET_NOTIF_KEY     = "betNotifKey";
    public static final String CTX_BET_TYPE          = "betType";
    public static final String CTX_BET_MATCH_TITLE   = "betMatchTitle";
    public static final String CTX_BET_MATCH_URL     = "betMatchUrl";
    public static final String CTX_BET_BOOKMAKER     = "betBookmaker";
    public static final String CTX_BET_ODDS          = "betOdds";
    public static final String CTX_BET_AMOUNT        = "betAmount";
    public static final String CTX_BET_PARTS_JSON    = "betPartsJson";    // JSON list of ParticipantRequest
    public static final String CTX_BET_ACCOUNT_ID    = "betAccountId";    // selected BetAccountEntity UUID
    public static final String CTX_BET_WIZARD_MSG    = "betWizardMsg";    // wizard message ID
    public static final String CTX_BET_EXPRESS_JSON  = "betExpressJson";  // JSON list of BetSlipRequest legs
    public static final String CTX_BET_EDITING_PART_ID  = "betEditingPartId"; // personId whose stake is being edited
    public static final String CTX_ACCT_EDIT_ID         = "acctEditId";       // UUID of account currently being edited
    public static final String CTX_ACCT_EDIT_PERSON_ID  = "acctEditPersonId"; // UUID of person being linked/edited in account
    public static final String CTX_ACCT_EDIT_OP         = "acctEditOp";       // "SET" | "ADD" | "SUB"
}
