-- V44: explicit opt-in link between a Telegram user's personal chat and a group's betting
-- journal, so the user can manage that group's bets from DM without spamming the group.
--
-- Deliberately NOT a revival of the old chat_members table (dropped in V23) — that was a
-- passive "everyone ever seen in this chat" tracker for a different purpose (an abandoned
-- participant-picker tied to real accounts). This is a small, explicit, per-user opt-in.
CREATE TABLE bet_dm_links (
    chat_id     BIGINT       NOT NULL,
    telegram_id BIGINT       NOT NULL,
    chat_title  VARCHAR(255),
    linked_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, telegram_id)
);

CREATE INDEX idx_bet_dm_links_telegram ON bet_dm_links(telegram_id);
