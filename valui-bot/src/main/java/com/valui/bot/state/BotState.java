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
    BETTING_WAITING_TITLE,        // entering match title
    BETTING_WAITING_ODDS,         // entering coefficient
    BETTING_WAITING_AMOUNT,       // entering stake amount
    BETTING_CONFIRM,              // confirmation screen before submit
    BETTING_WAITING_PART_AMOUNT,  // entering custom stake amount for one participant
    BETTING_WAITING_PAYOUT,       // entering manual payout after express win

    // ── Controller event search ───────────────────────────────────────────────
    SEARCHING_CONTROLLER_EVENTS,           // waiting for user to type event search query

    // ── Account / Person management ───────────────────────────────────────────
    BETTING_WAITING_ACCOUNT_NAME,          // entering name for new bet account
    BETTING_WAITING_PERSON_NAME,           // entering name for new bet person
    BETTING_WAITING_ACCOUNT_PERSON_BALANCE, // entering initial/updated balance for a person in an account

    // ── Top-up via CryptoBot ──────────────────────────────────────────────────
    TOPUP_ENTER_AMOUNT,                    // waiting for user to type token amount
}
