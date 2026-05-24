-- Add optimistic-lock version column to bets table.
-- Prevents lost updates when two concurrent requests attempt to resolve the same bet.
ALTER TABLE bets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
