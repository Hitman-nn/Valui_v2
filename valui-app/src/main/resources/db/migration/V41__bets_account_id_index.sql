-- Index for queries by account_id on bets (getAccountStats, listBets with accountId filter).
-- V23 added the account_id column but omitted this index.
CREATE INDEX IF NOT EXISTS idx_bets_account_id ON bets(account_id) WHERE account_id IS NOT NULL;
