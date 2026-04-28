-- Attach the Telegram chat where notifications should be delivered.
-- NULL = legacy row (treat as user's personal chat, i.e. same as user.telegram_id).
-- Negative values = group / supergroup chat.
ALTER TABLE controllers
    ADD COLUMN notification_chat_id BIGINT;
