-- V25: Store the Telegram message_id in notification_log to enable editing
-- already-sent messages when the same match appears under a different event ID
-- (e.g., BetBoom pre-match → live transition within the dedup TTL window).

ALTER TABLE notification_log
    ADD COLUMN telegram_message_id BIGINT NULL;

COMMENT ON COLUMN notification_log.telegram_message_id
    IS 'Telegram integer message ID returned by sendMessage; used for editMessageText within the dedup TTL.';
