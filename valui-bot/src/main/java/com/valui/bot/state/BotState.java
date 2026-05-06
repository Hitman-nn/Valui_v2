package com.valui.bot.state;

public enum BotState {
    IDLE,
    WAITING_FILTER_RULE,
    WAITING_CONTROLLER_URL,
    WAITING_CONFIRM_DELETE,
    WAITING_CONFIRM_CREATE,
    SELECTING_BOOKMAKER,
    SELECTING_SPORT,
    SELECTING_TOURNAMENT,
    WAITING_WIZARD_SEARCH,

    // ── Betting wizard ────────────────────────────────────────────────────────
    BETTING_WAITING_TITLE,    // entering match title
    BETTING_WAITING_ODDS,     // entering coefficient
    BETTING_WAITING_AMOUNT,   // entering stake amount
    BETTING_WAITING_PARTICIPANT,  // entering partner's Telegram ID
    BETTING_CONFIRM,              // confirmation screen before submit
    BANKING_SELECTING_OWNER,      // choosing owner from chat participant list
    BANKING_WAITING_NAME,         // entering new bank account name (legacy, kept for compat)
    BANKING_WAITING_BALANCE,      // entering initial balance for auto-named account
    BANKING_EDITING_BALANCE,      // entering new absolute balance for existing account
    BETTING_WAITING_PART_AMOUNT   // entering custom stake amount for one participant
}
