-- V23: Redesign betting — chat-scoped accounts, manual persons, P&L at resolution only

-- ── Drop old banking / chat-member tables ────────────────────────────────────
ALTER TABLE bet_participants DROP COLUMN IF EXISTS bank_account_id;
DROP TABLE IF EXISTS bank_accounts  CASCADE;
DROP TABLE IF EXISTS chat_members   CASCADE;

-- ── bet_accounts: named wallets shared per chat ──────────────────────────────
CREATE TABLE bet_accounts (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id    BIGINT       NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bet_accounts_chat ON bet_accounts(chat_id);

-- ── bet_persons: manually created participants per chat ───────────────────────
CREATE TABLE bet_persons (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id      BIGINT       NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_bet_persons_chat ON bet_persons(chat_id);

-- ── bet_person_balances: sub-ledger (person's balance within an account) ──────
CREATE TABLE bet_person_balances (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID          NOT NULL REFERENCES bet_accounts(id) ON DELETE CASCADE,
    person_id  UUID          NOT NULL REFERENCES bet_persons(id)  ON DELETE CASCADE,
    balance    NUMERIC(12,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_person_balance UNIQUE (account_id, person_id)
);
CREATE INDEX idx_bet_person_balances_account ON bet_person_balances(account_id);
CREATE INDEX idx_bet_person_balances_person  ON bet_person_balances(person_id);

-- ── bets: add reference to the tracking account ──────────────────────────────
ALTER TABLE bets
    ADD COLUMN account_id UUID REFERENCES bet_accounts(id) ON DELETE SET NULL;

-- ── bet_participants: replace telegram linkage with person linkage ────────────
ALTER TABLE bet_participants
    ADD COLUMN person_id UUID REFERENCES bet_persons(id) ON DELETE SET NULL;

ALTER TABLE bet_participants
    ALTER COLUMN telegram_id DROP NOT NULL;
