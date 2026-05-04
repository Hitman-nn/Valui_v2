-- Add chat_id to notification_log so we can deduplicate per (event, chat, channel).
-- Negative values = group/supergroup; positive = personal chat.
ALTER TABLE notification_log
    ADD COLUMN chat_id BIGINT;

-- Partial unique index: one pending/sent row per event × chat × channel.
-- NULL values excluded so legacy rows without chat_id never collide.
CREATE UNIQUE INDEX uq_notification_log_event_chat_channel
    ON notification_log (event_id, chat_id, channel)
    WHERE event_id IS NOT NULL AND chat_id IS NOT NULL;
