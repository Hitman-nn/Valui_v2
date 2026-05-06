package com.valui.bot.keyboard;

import java.util.UUID;


/**
 * Central registry of callback_data strings.
 * Format convention: "ACTION:PARAM1:PARAM2"
 */
public final class CallbackData {

    private CallbackData() {}

    // ─── Global navigation ────────────────────────────────────────────────────
    public static final String MENU_MAIN         = "MENU:MAIN";
    public static final String MENU_SUBSCRIPTION = "MENU:SUBS";
    public static final String MENU_HELP         = "MENU:HELP";
    public static final String CANCEL            = "CANCEL";

    /** Placed on display-only buttons (e.g. page counter) that must not trigger logic. */
    public static final String NOOP = ".";

    // ─── Controller — format: "CTRL:ACTION:uuid" ──────────────────────────────
    public static final String CTRL_LIST    = "CTRL:LIST";
    public static final String CTRL_BK_LIST = "CTRL:BK:LIST";

    public static String ctrlDetail(UUID id)      { return "CTRL:DETAIL:" + id; }
    public static String ctrlStop(UUID id)         { return "CTRL:STOP:" + id; }
    public static String ctrlMute(UUID id)         { return "CTRL:MUTE:" + id; }
    public static String ctrlUnmute(UUID id)       { return "CTRL:UNMUTE:" + id; }
    public static String ctrlFilterEdit(UUID id)   { return "CTRL:FILTER:" + id; }
    /** Shows controllers for a specific bookmaker: "CTRL:BK:{BM}" */
    public static String ctrlByBookmaker(String bm) { return "CTRL:BK:" + bm.toUpperCase(); }

    // ─── Filter — format: "FILTER:ACTION:uuid" ───────────────────────────────
    public static final String FILTER_LIST   = "FILTER:LIST";
    public static final String FILTER_ADD    = "FILTER:ADD";

    public static String filterDelete(UUID id) { return "FILTER:DELETE:" + id; }
    public static String filterEdit(UUID id)   { return "FILTER:EDIT:" + id; }

    // ─── Bookmaker — format: "BK:SELECT:CODE" ────────────────────────────────
    public static final String BK_SELECT_PREFIX = "BK:SELECT:";

    public static String bookmakerSelect(String code) { return BK_SELECT_PREFIX + code; }

    // ─── Wizard: Sport — "SPORT:SEL:{id}" | "SPORT:PAGE:{n}" ────────────────
    public static final String SPORT_SEL_PREFIX  = "SPORT:SEL:";
    public static final String SPORT_PAGE_PREFIX = "SPORT";   // used as navigationCallbackPrefix in PagedKeyboardBuilder
    public static final String SPORT_BACK        = "SPORT:BACK";

    public static String sportSel(String sportId) { return SPORT_SEL_PREFIX + sportId; }

    // ─── Wizard: Tournament — "TOURN:SEL:{id}" | "TOURN:PAGE:{n}" | "TOURN:ALL" ──
    public static final String TOURN_SEL_PREFIX  = "TOURN:SEL:";
    public static final String TOURN_PAGE_PREFIX = "TOURN";   // used as navigationCallbackPrefix
    public static final String TOURN_ALL         = "TOURN:ALL";
    public static final String TOURN_EXIST       = "TOURN:EXIST";
    public static final String TOURN_BACK        = "TOURN:BACK";

    public static String tournSel(String tournamentId) { return TOURN_SEL_PREFIX + tournamentId; }

    // ─── Wizard: Filter skip (individual controller filter) ─────────────────
    public static final String FILTER_SKIP = "FSKIP";

    // ─── Wizard: Controller confirmation ────────────────────────────────────
    public static final String CTRL_CONFIRM_PREFIX = "CCONF:";
    public static final String CTRL_CONFIRM_YES    = "CCONF:YES";
    public static final String CTRL_CONFIRM_NO     = "CCONF:NO";

    // ─── Language — "LANG:SET:{code}" ────────────────────────────────────────
    public static final String LANG_SET_PREFIX = "LANG:SET:";

    public static String langSet(String langCode) { return LANG_SET_PREFIX + langCode; }

    // ─── Plans — "PLANS:VIEW", "PLANS:SELECT:{code}" ────────────────────────
    public static final String PLANS_VIEW = "PLANS:VIEW";

    public static String plansSelect(String planCode) { return "PLANS:SELECT:" + planCode; }

    // ─── Wizard search ───────────────────────────────────────────────────────
    public static final String SEARCH_SPORT = "SEARCH:SPORT";
    public static final String SEARCH_TOURN = "SEARCH:TOURN";

    // ─── Quick-add from notification — "QADD:{notificationLogId}" ───────────
    /** Max 64 bytes: "QADD:" (5) + UUID (36) = 41 bytes. */
    public static final String QADD_PREFIX = "QADD:";

    public static String qadd(String notificationLogId) { return QADD_PREFIX + notificationLogId; }

    // ─── Betting Journal ──────────────────────────────────────────────────────
    /**
     * "💸 Поставил" from notification: "BET:NOTIF:{notifLogId}" → shows type choice.
     * After choice: "BET:NOTIF:S:{key}" for single, "BET:NOTIF:E:{key}" for express.
     * All fit in 64 bytes: 12 + UUID(36) = 48.
     */
    public static final String BET_NOTIF_PREFIX         = "BET:NOTIF:";
    public static final String BET_NOTIF_SINGLE_PREFIX  = "BET:NOTIF:S:";
    public static final String BET_NOTIF_EXPRESS_PREFIX = "BET:NOTIF:E:";
    public static final String BET_MENU             = "BET:MENU";
    public static final String BET_NEW_SINGLE       = "BET:NEW:SINGLE";
    public static final String BET_NEW_EXPRESS      = "BET:NEW:EXPRESS";
    public static final String BET_EXPRESS_ADD      = "BET:EXPR:ADD";
    public static final String BET_EXPRESS_DONE     = "BET:EXPR:DONE";
    public static final String BET_CONFIRM          = "BET:CONFIRM";
    public static final String BET_LIST_OPEN        = "BET:LIST:OPEN";
    public static final String BET_LIST_ALL         = "BET:LIST:ALL";
    public static final String BET_STAT             = "BET:STAT";
    public static final String BET_CANCEL_WIZARD    = "BET:CANCEL";
    public static final String BET_ADD_PARTICIPANT  = "BET:PART:ADD";
    public static final String BET_PART_STEP        = "BET:PART:STEP";   // back to participant step from confirmation
    public static final String BET_PART_DONE        = "BET:PART:DONE";   // done with participant selection
    public static final String BET_PART_TOGGLE_PREFIX = "BET:PT:";        // BET:PT:{telegramId} — toggle participant
    public static final String BET_PART_SPLIT_PREFIX  = "BET:PS:";        // BET:PS:{myN}:{partN} — set ratio
    public static final String BET_PART_AMT_PREFIX    = "BET:PA:";        // BET:PA:{telegramId}  — edit stake for participant
    public static final String BET_DETAIL_PREFIX    = "BET:D:";
    public static final String BET_RESOLVE_PREFIX   = "BET:R:";    // BET:R:{id}:WIN|LOSE|RETURN
    public static final String BET_CANCEL_PREFIX    = "BET:C:";    // BET:C:{betId}

    /** Bank accounts */
    public static final String BANK_LIST          = "BANK:LIST";
    public static final String BANK_NEW           = "BANK:NEW";
    public static final String BANK_BALANCE_SKIP  = "BANK:BAL:0";  // skip initial balance (set 0)
    public static final String BANK_OWN_PREFIX    = "BANK:OWN:";   // BANK:OWN:{telegramId} — owner selected
    public static final String BANK_SEL_PREFIX    = "BANK:SEL:";   // BANK:SEL:{accountId}
    public static final String BANK_DEF_PREFIX    = "BANK:DEF:";   // BANK:DEF:{accountId}
    public static final String BANK_DEL_PREFIX    = "BANK:DEL:";   // BANK:DEL:{accountId}
    public static final String BANK_EDIT_PREFIX   = "BANK:E:";     // BANK:E:{accountId} — edit balance

    public static String bankOwn(long telegramId) { return BANK_OWN_PREFIX + telegramId; }

    public static String betNotif(String notifLogId)        { return BET_NOTIF_PREFIX + notifLogId; }
    public static String betPartToggle(long telegramId)    { return BET_PART_TOGGLE_PREFIX + telegramId; }
    /** Ratio split: myN parts for me, partN parts for partner (e.g. 1:1, 2:1, 3:2). */
    public static String betPartSplit(int myN, int partN)  { return BET_PART_SPLIT_PREFIX + myN + ":" + partN; }
    public static String betPartAmtEdit(long telegramId)   { return BET_PART_AMT_PREFIX + telegramId; }
    public static String bankEdit(String accountId)        { return BANK_EDIT_PREFIX + accountId; }
    public static String betNotifSingle(String notifLogId) { return BET_NOTIF_SINGLE_PREFIX + notifLogId; }
    public static String betNotifExpress(String notifLogId) { return BET_NOTIF_EXPRESS_PREFIX + notifLogId; }
    public static String betDetail(String betId)        { return BET_DETAIL_PREFIX + betId; }
    public static String betResolve(String betId, String result) { return BET_RESOLVE_PREFIX + betId + ":" + result; }
    public static String betCancel(String betId)        { return BET_CANCEL_PREFIX + betId; }
    public static String bankSel(String accountId)      { return BANK_SEL_PREFIX + accountId; }
    public static String bankDef(String accountId)      { return BANK_DEF_PREFIX + accountId; }
    public static String bankDel(String accountId)      { return BANK_DEL_PREFIX + accountId; }

    // ─── Pagination ───────────────────────────────────────────────────────────
    /** Generic pagination — format: "PAGE:{listKey}:{n}" (used by controller/tournament lists). */
    public static String page(String listKey, int pageNum) {
        return "PAGE:" + listKey + ":" + pageNum;
    }

    /** Bet-list pagination — format: "BET:LIST:OPEN:P:{n}" / "BET:LIST:ALL:P:{n}". */
    public static String betListPage(boolean openOnly, int pageNum) {
        return (openOnly ? BET_LIST_OPEN : BET_LIST_ALL) + ":P:" + pageNum;
    }
}
