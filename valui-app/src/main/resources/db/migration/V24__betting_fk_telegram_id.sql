-- V24: Add FK constraints from bets/bet_participants telegram_id to users(telegram_id).
--
-- Why deferred until now: V20 created telegram_id as BIGINT NOT NULL (bets) and
-- BIGINT NOT NULL (bet_participants), with no FK — data integrity was unenforced.
-- V23 already dropped NOT NULL on bet_participants.telegram_id.
-- This migration adds the FK relationships so orphan telegram_id references are
-- caught at the DB level, and drops NOT NULL on bets.telegram_id first to allow
-- ON DELETE SET NULL (preserving historical bets when a user account is deleted).

-- ── bets.telegram_id ─────────────────────────────────────────────────────────

-- Allow null so ON DELETE SET NULL works (user deletion keeps betting history)
ALTER TABLE bets
    ALTER COLUMN telegram_id DROP NOT NULL;

ALTER TABLE bets
    ADD CONSTRAINT fk_bets_telegram_id
        FOREIGN KEY (telegram_id)
        REFERENCES users(telegram_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE;

-- ── bet_participants.telegram_id ──────────────────────────────────────────────

-- NOT NULL was already dropped in V23; just add the FK.
ALTER TABLE bet_participants
    ADD CONSTRAINT fk_bet_participants_telegram_id
        FOREIGN KEY (telegram_id)
        REFERENCES users(telegram_id)
        ON DELETE SET NULL
        ON UPDATE CASCADE;
