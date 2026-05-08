-- V26: Index to support efficient nightly purge of old sent outbox rows.
-- OutboxPurgeService deletes rows where sent_at IS NOT NULL and sent_at < cutoff.
-- Without this index the DELETE would require a full sequential scan.

CREATE INDEX idx_outbox_sent_at ON outbox_events (sent_at)
    WHERE sent_at IS NOT NULL;
