-- V20: Betting Journal — bank accounts, bets, bet_slips, bet_participants

-- ─── bank_accounts ───────────────────────────────────────────────────────────
CREATE TABLE bank_accounts (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_telegram_id BIGINT      NOT NULL,
    name              VARCHAR(128) NOT NULL,
    balance           NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency          VARCHAR(3)  NOT NULL DEFAULT 'RUB',
    is_default        BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bank_accounts_owner ON bank_accounts(owner_telegram_id);

-- ─── bets ────────────────────────────────────────────────────────────────────
CREATE TABLE bets (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    telegram_id      BIGINT        NOT NULL,
    chat_id          BIGINT        NOT NULL,
    type             VARCHAR(20)   NOT NULL,       -- SINGLE | EXPRESS
    status           VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    total_odds       NUMERIC(10,4) NOT NULL,
    total_stake      NUMERIC(12,2) NOT NULL,
    potential_payout NUMERIC(12,2) NOT NULL,
    actual_payout    NUMERIC(12,2),
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_bets_telegram_id ON bets(telegram_id);
CREATE INDEX idx_bets_chat_id     ON bets(chat_id);
CREATE INDEX idx_bets_status      ON bets(status);

-- ─── bet_slips ───────────────────────────────────────────────────────────────
CREATE TABLE bet_slips (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id      UUID          NOT NULL REFERENCES bets(id) ON DELETE CASCADE,
    match_title TEXT          NOT NULL,
    match_url   TEXT,
    bookmaker   VARCHAR(32),
    odds        NUMERIC(8,4)  NOT NULL,
    result      VARCHAR(20)   NOT NULL DEFAULT 'OPEN',   -- OPEN | WON | LOST | RETURNED
    resolved_at TIMESTAMPTZ,
    sort_order  INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_bet_slips_bet_id ON bet_slips(bet_id);

-- ─── bet_participants ────────────────────────────────────────────────────────
CREATE TABLE bet_participants (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    bet_id          UUID          NOT NULL REFERENCES bets(id) ON DELETE CASCADE,
    telegram_id     BIGINT        NOT NULL,
    display_name    VARCHAR(128),
    stake           NUMERIC(12,2) NOT NULL,
    profit_share    NUMERIC(5,4)  NOT NULL DEFAULT 1.0000,
    bank_account_id UUID          REFERENCES bank_accounts(id) ON DELETE SET NULL
);

CREATE INDEX idx_bet_participants_bet_id     ON bet_participants(bet_id);
CREATE INDEX idx_bet_participants_telegram   ON bet_participants(telegram_id);
