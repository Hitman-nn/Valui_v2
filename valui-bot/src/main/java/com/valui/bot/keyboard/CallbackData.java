package com.valui.bot.keyboard;

import java.util.UUID;

/**
 * Central registry of callback_data strings.
 * Format convention: "ACTION:PARAM1:PARAM2"
 */
public final class CallbackData {

    private CallbackData() {}

    // ─── Global navigation ────────────────────────────────────────────────────
    public static final String MENU_MAIN = "MENU:MAIN";
    public static final String MENU_HELP = "MENU:HELP";
    public static final String CANCEL            = "CANCEL";

    /** Display-only button that must not trigger logic. */
    public static final String NOOP = ".";

    // ─── Controller ───────────────────────────────────────────────────────────
    public static final String CTRL_LIST             = "CTRL:LIST";
    public static final String CTRL_ADD              = "CTRL:ADD";
    public static final String CTRL_BK_LIST          = "CTRL:BK:LIST";
    public static final String CTRL_LIST_SORT_PREFIX = "CTRL:LIST:SORT:";

    public static String ctrlListSort(String sort) { return CTRL_LIST_SORT_PREFIX + sort.toUpperCase(); }

    public static String ctrlDetail(UUID id)    { return "CTRL:DETAIL:" + id; }
    public static String ctrlStop(UUID id)      { return "CTRL:STOP:" + id; }
    public static String ctrlMute(UUID id)      { return "CTRL:MUTE:" + id; }
    public static String ctrlUnmute(UUID id)    { return "CTRL:UNMUTE:" + id; }
    public static String ctrlFilterEdit(UUID id){ return "CTRL:FILTER:" + id; }
    public static String ctrlByBookmaker(String bm)       { return "CTRL:BK:" + bm.toUpperCase(); }
    public static String ctrlByBookmakerSort(String bm, String sort) {
        return "CTRL:BK:" + bm.toUpperCase() + ":SORT:" + sort.toUpperCase();
    }

    // ─── Controller events ────────────────────────────────────────────────────
    public static final String CTRL_EVT_BET_PREFIX  = "CTRL:EVT:BET:";
    public static final String CTRL_EVT_SRCH_PREFIX = "CTRL:EVT:SRCH:";

    public static String ctrlEvtBet(UUID eventId)                      { return CTRL_EVT_BET_PREFIX  + eventId; }
    public static String ctrlEvtSrch(UUID controllerId)                { return CTRL_EVT_SRCH_PREFIX + controllerId; }
    public static String ctrlEvtSrchPage(UUID controllerId, int page)  { return CTRL_EVT_SRCH_PREFIX + controllerId + ":P:" + page; }
    public static String ctrlDetailPage(UUID controllerId, int page)   { return "CTRL:DETAIL:" + controllerId + ":P:" + page; }

    // ─── Filter ───────────────────────────────────────────────────────────────
    public static final String FILTER_LIST = "FILTER:LIST";
    public static final String FILTER_ADD  = "FILTER:ADD";

    public static String filterDelete(UUID id) { return "FILTER:DELETE:" + id; }
    public static String filterEdit(UUID id)   { return "FILTER:EDIT:" + id; }

    // ─── Bookmaker ────────────────────────────────────────────────────────────
    public static final String BK_SELECT_PREFIX = "BK:SELECT:";
    public static String bookmakerSelect(String code) { return BK_SELECT_PREFIX + code; }

    // ─── Wizard: Sport ────────────────────────────────────────────────────────
    public static final String SPORT_SEL_PREFIX  = "SPORT:SEL:";
    public static final String SPORT_PAGE_PREFIX = "SPORT";
    public static final String SPORT_BACK        = "SPORT:BACK";
    public static String sportSel(String sportId) { return SPORT_SEL_PREFIX + sportId; }

    // ─── Wizard: Tournament ───────────────────────────────────────────────────
    public static final String TOURN_SEL_PREFIX  = "TOURN:SEL:";
    public static final String TOURN_PAGE_PREFIX = "TOURN";
    public static final String TOURN_ALL         = "TOURN:ALL";
    public static final String TOURN_EXIST       = "TOURN:EXIST";
    public static final String TOURN_BACK        = "TOURN:BACK";
    public static String tournSel(String id) { return TOURN_SEL_PREFIX + id; }

    // ─── Wizard: Filter / Confirm ─────────────────────────────────────────────
    public static final String FILTER_SKIP          = "FSKIP";
    public static final String CTRL_CONFIRM_PREFIX  = "CCONF:";
    public static final String CTRL_CONFIRM_YES     = "CCONF:YES";
    public static final String CTRL_CONFIRM_NO      = "CCONF:NO";

    // ─── Language ─────────────────────────────────────────────────────────────
    public static final String LANG_SET_PREFIX = "LANG:SET:";
    public static String langSet(String code) { return LANG_SET_PREFIX + code; }

    // ─── Wizard search ────────────────────────────────────────────────────────
    public static final String SEARCH_SPORT = "SEARCH:SPORT";
    public static final String SEARCH_TOURN = "SEARCH:TOURN";

    // ─── Quick-add ────────────────────────────────────────────────────────────
    public static final String QADD_PREFIX = "QADD:";
    public static String qadd(String notifLogId) { return QADD_PREFIX + notifLogId; }

    // ─── Betting Journal: notifications ──────────────────────────────────────
    public static final String BET_NOTIF_PREFIX         = "BET:NOTIF:";
    public static final String BET_NOTIF_SINGLE_PREFIX  = "BET:NOTIF:S:";
    public static final String BET_NOTIF_EXPRESS_PREFIX = "BET:NOTIF:E:";

    public static String betNotif(String id)        { return BET_NOTIF_PREFIX + id; }
    public static String betNotifSingle(String id)  { return BET_NOTIF_SINGLE_PREFIX + id; }
    public static String betNotifExpress(String id) { return BET_NOTIF_EXPRESS_PREFIX + id; }

    // ─── Betting Journal: wizard / navigation ────────────────────────────────
    public static final String BET_MENU          = "BET:MENU";
    public static final String BET_NEW_SINGLE    = "BET:NEW:SINGLE";
    public static final String BET_NEW_EXPRESS   = "BET:NEW:EXPRESS";
    public static final String BET_EXPRESS_ADD   = "BET:EXPR:ADD";
    public static final String BET_EXPRESS_DONE  = "BET:EXPR:DONE";
    public static final String BET_CONFIRM       = "BET:CONFIRM";
    public static final String BET_LIST_OPEN     = "BET:LIST:OPEN";
    public static final String BET_LIST_ALL      = "BET:LIST:ALL";
    public static final String BET_STAT              = "BET:STAT";
    /** BET:STAT:ACCT:{accountId} — stats for one account. UUID(36) → 50 chars. */
    public static final String BET_STAT_ACCT_PREFIX  = "BET:STAT:ACCT:";
    public static String betStatAcct(String accountId) { return BET_STAT_ACCT_PREFIX + accountId; }
    public static final String BET_CANCEL_WIZARD = "BET:CANCEL";

    // ─── Betting Journal: DM chat picker ──────────────────────────────────────
    /** BET:CHAT:{chatId} — pick which linked group's betting journal to operate on from DM. */
    public static final String BET_CHAT_SEL_PREFIX = "BET:CHAT:";
    public static String betChatSel(long chatId) { return BET_CHAT_SEL_PREFIX + chatId; }

    // ─── Controllers: DM chat picker ──────────────────────────────────────────
    /** CTRL:CHAT:PICK — open the linked-chat picker; CTRL:CHAT:{chatId} — pick which
     *  linked group's controllers to browse from DM. Shares the BettingChatResolver
     *  selection with the betting journal's BET:CHAT: picker. */
    public static final String CTRL_CHAT_SEL_PREFIX = "CTRL:CHAT:";
    public static final String CTRL_CHAT_PICK       = CTRL_CHAT_SEL_PREFIX + "PICK";
    public static String ctrlChatSel(long chatId) { return CTRL_CHAT_SEL_PREFIX + chatId; }

    // ─── Betting Journal: participant step ────────────────────────────────────
    public static final String BET_ADD_PARTICIPANT  = "BET:PART:ADD";
    public static final String BET_PART_STEP        = "BET:PART:STEP";
    public static final String BET_PART_DONE        = "BET:PART:DONE";
    /** BET:PT:{personId} — toggle person selection. UUID(36) → total 43 chars. */
    public static final String BET_PART_TOGGLE_PREFIX = "BET:PT:";
    /** BET:PS:{myN}:{partN} — ratio split. */
    public static final String BET_PART_SPLIT_PREFIX  = "BET:PS:";
    /** BET:PA:{personId} — edit stake for one participant. UUID(36) → total 43 chars. */
    public static final String BET_PART_AMT_PREFIX    = "BET:PA:";

    public static String betPartToggle(String personId)         { return BET_PART_TOGGLE_PREFIX + personId; }
    public static String betPartSplit(int myN, int partN)       { return BET_PART_SPLIT_PREFIX + myN + ":" + partN; }
    public static String betPartAmtEdit(String personId)        { return BET_PART_AMT_PREFIX + personId; }

    // ─── Betting Journal: account step (select wallet in wizard) ────────────
    public static final String BET_ACCT_STEP        = "BET:ACCT:STEP";
    /** BET:ACCT:SEL:{accountId} — select account for the current bet. */
    public static final String BET_ACCT_SEL_PREFIX  = "BET:ACCT:SEL:";
    public static String betAcctSel(String accountId) { return BET_ACCT_SEL_PREFIX + accountId; }

    // ─── Betting Journal: bet detail / resolve ────────────────────────────────
    public static final String BET_DETAIL_PREFIX       = "BET:D:";
    public static final String BET_RESOLVE_PREFIX      = "BET:R:";   // BET:R:{betId}:WIN|LOSE|RETURN
    public static final String BET_CANCEL_PREFIX        = "BET:C:";   // BET:C:{betId}
    /** BET:DEL:{betId} — ask confirmation. BET:DELC:{betId} — execute delete. */
    public static final String BET_DELETE_PREFIX        = "BET:DEL:";
    public static final String BET_DELETE_CONFIRM_PREFIX = "BET:DELC:";
    public static final String BET_SLIP_RESOLVE_PREFIX = "BET:SI:";  // BET:SI:{betId}:{sortOrder}:W|L|R
    /** BET:WPAY:{betId}:{amount} — confirm payout; BET:WPAY:M:{betId} — enter manually. */
    public static final String BET_WIN_PAY_PREFIX       = "BET:WPAY:";

    public static String betDetail(String betId)  { return BET_DETAIL_PREFIX + betId; }
    public static String betResolve(String betId, String result) { return BET_RESOLVE_PREFIX + betId + ":" + result; }
    public static String betCancel(String betId)         { return BET_CANCEL_PREFIX + betId; }
    public static String betDelete(String betId)         { return BET_DELETE_PREFIX + betId; }
    public static String betDeleteConfirm(String betId)  { return BET_DELETE_CONFIRM_PREFIX + betId; }
    public static String betSlipResolve(String betId, int sortOrder, String result) {
        return BET_SLIP_RESOLVE_PREFIX + betId + ":" + sortOrder + ":" + result;
    }
    public static String betWinPay(String betId, String amount) { return BET_WIN_PAY_PREFIX + betId + ":" + amount; }
    public static String betWinPayManual(String betId)          { return BET_WIN_PAY_PREFIX + "M:" + betId; }

    // ─── Accounts (BetAccountCallback: ACCT:) ────────────────────────────────
    public static final String ACCT_LIST            = "ACCT:LIST";
    public static final String ACCT_NEW             = "ACCT:NEW";
    public static final String ACCT_EDIT_PREFIX     = "ACCT:EDIT:"; // ACCT:EDIT:{accountId} — open edit screen
    public static final String ACCT_DEL_PREFIX      = "ACCT:DEL:";  // ACCT:DEL:{accountId}
    public static final String ACCT_PERS_TOGGLE_PREFIX = "ACCT:PT:"; // ACCT:PT:{personId} — add unlinked person (prompt balance)
    public static final String ACCT_PERS_BAL_PREFIX    = "ACCT:PB:"; // ACCT:PB:{personId} — set absolute balance
    public static final String ACCT_PERS_ADD_PREFIX    = "ACCT:P+:"; // ACCT:P+:{personId} — deposit (balance += amount)
    public static final String ACCT_PERS_SUB_PREFIX    = "ACCT:P-:"; // ACCT:P-:{personId} — withdrawal (balance -= amount)
    public static final String ACCT_PERS_REM_PREFIX    = "ACCT:PR:"; // ACCT:PR:{personId} — remove person from account

    public static String acctEdit(String id)       { return ACCT_EDIT_PREFIX + id; }
    public static String acctDel(String id)        { return ACCT_DEL_PREFIX + id; }
    public static String acctPersToggle(String id) { return ACCT_PERS_TOGGLE_PREFIX + id; }
    public static String acctPersBal(String id)    { return ACCT_PERS_BAL_PREFIX + id; }
    public static String acctPersAdd(String id)    { return ACCT_PERS_ADD_PREFIX + id; }
    public static String acctPersSub(String id)    { return ACCT_PERS_SUB_PREFIX + id; }
    public static String acctPersRem(String id)    { return ACCT_PERS_REM_PREFIX + id; }

    // ─── Persons (BetPersonCallback: PERS:) ──────────────────────────────────
    public static final String PERS_LIST       = "PERS:LIST";
    public static final String PERS_NEW        = "PERS:NEW";
    public static final String PERS_DEL_PREFIX = "PERS:DEL:";   // PERS:DEL:{personId}
    public static final String PERS_STAT_PREFIX = "PERS:STAT:"; // PERS:STAT:{personId}

    public static String persDel(String id)  { return PERS_DEL_PREFIX + id; }
    public static String persStat(String id) { return PERS_STAT_PREFIX + id; }

    // ─── Stop confirmation ────────────────────────────────────────────────────
    public static final String STOP_CONFIRM_PREFIX = "STOP:";
    public static final String STOP_YES = "STOP:YES";
    public static final String STOP_NO  = "STOP:NO";

    // ─── Settings callbacks ───────────────────────────────────────────────────
    public static final String SETT_LANG_PREFIX = "SETT:LANG:";
    public static final String SETT_TOK_PREFIX  = "SETT:TOK:";
    public static final String SETT_TOK_OFF     = "SETT:TOK:OFF";
    public static String settLang(String code) { return SETT_LANG_PREFIX + code; }
    public static String settTok(int value)    { return SETT_TOK_PREFIX + value; }

    // ─── VK ──────────────────────────────────────────────────────────────────
    public static final String VK_LINK   = "VK:LINK";
    public static final String VK_UNLINK = "VK:UNLINK";

    // ─── Top-up (CryptoBot) ───────────────────────────────────────────────────
    public static final String TOPUP_START           = "TOPUP:START";
    public static final String TOPUP_CURRENCY_PREFIX = "TOPUP:CUR:";
    public static String topupCurrency(String currency) { return TOPUP_CURRENCY_PREFIX + currency; }

    // ─── Print (PrintCallback: PRINT:) ───────────────────────────────────────
    public static final String PRINT_MENU     = "PRINT:MENU";
    public static final String PRINT_GO       = "PRINT:GO";
    public static final String PRINT_SEL_ALL  = "PRINT:ALL";
    public static final String PRINT_ACCT_PREFIX  = "PRINT:A:";   // PRINT:A:{accountId}
    public static final String PRINT_PERS_PREFIX  = "PRINT:P:";   // PRINT:P:{personId}
    public static final String PRINT_TOGGLE_PREFIX = "PRINT:T:";  // PRINT:T:B:{betId} or PRINT:T:X:{txId}
    public static final String PRINT_PAGE_PREFIX   = "PRINT:PG:"; // PRINT:PG:{n}

    public static String printAcct(String id)         { return PRINT_ACCT_PREFIX + id; }
    public static String printPers(String id)         { return PRINT_PERS_PREFIX + id; }
    public static String printToggleBet(String id)    { return PRINT_TOGGLE_PREFIX + "B:" + id; }
    public static String printToggleTx(String id)     { return PRINT_TOGGLE_PREFIX + "X:" + id; }
    public static String printPage(int n)              { return PRINT_PAGE_PREFIX + n; }

    // ─── Market watch ─────────────────────────────────────────────────────────
    public static final String WATCH_HCAP_PREFIX  = "WATCH:HCAP:";
    public static final String WATCH_TOTAL_PREFIX = "WATCH:TOTAL:";

    public static String watchHcap(String notifLogId)  { return WATCH_HCAP_PREFIX  + notifLogId; }
    public static String watchTotal(String notifLogId) { return WATCH_TOTAL_PREFIX + notifLogId; }

    // ─── Pagination ───────────────────────────────────────────────────────────
    public static String page(String listKey, int pageNum) { return "PAGE:" + listKey + ":" + pageNum; }
    public static String betListPage(boolean openOnly, int pageNum) {
        return (openOnly ? BET_LIST_OPEN : BET_LIST_ALL) + ":P:" + pageNum;
    }
}
