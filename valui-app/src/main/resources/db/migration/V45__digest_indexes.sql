-- Supports GROUP BY s.chatId in the weekly per-chat digest aggregation queries. chat_id is
-- currently only the trailing column of the composite PK (controller_id, chat_id), so a
-- chat-keyed GROUP BY has no leading index today.
CREATE INDEX idx_ctrl_subs_chat_id ON controller_subscriptions(chat_id);

-- Supports "notifications sent to chat X in the last N days" (WHERE chat_id = ? AND
-- created_at >= ?) — idx_notification_log_created_at has the wrong leading column for a
-- per-chat filter.
CREATE INDEX idx_notification_log_chat_id_created_at ON notification_log(chat_id, created_at);
