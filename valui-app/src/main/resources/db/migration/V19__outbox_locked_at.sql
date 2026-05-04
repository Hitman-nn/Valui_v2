-- Optimistic send-lock: set before Kafka send, cleared by sent_at on success.
-- Prevents publishImmediate() and scanAndSend() from sending the same row concurrently.
ALTER TABLE outbox_events
    ADD COLUMN locked_at TIMESTAMPTZ;
