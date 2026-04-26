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
    public static final String CTRL_LIST = "CTRL:LIST";

    public static String ctrlDetail(UUID id) { return "CTRL:DETAIL:" + id; }
    public static String ctrlDelete(UUID id) { return "CTRL:DELETE:" + id; }
    public static String ctrlToggle(UUID id) { return "CTRL:TOGGLE:" + id; }

    // ─── Filter — format: "FILTER:ACTION:index" ───────────────────────────────
    public static final String FILTER_LIST = "FILTER:LIST";

    public static String filterDelete(int index) { return "FILTER:DELETE:" + index; }

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

    // ─── Wizard: Filter skip ─────────────────────────────────────────────────
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

    // ─── Pagination — format: "PAGE:{listKey}:{pageNum}" ─────────────────────
    public static String page(String listKey, int pageNum) {
        return "PAGE:" + listKey + ":" + pageNum;
    }
}
