-- ─── users ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_users_telegram_id ON users (telegram_id);
CREATE INDEX idx_users_status      ON users (status);

-- ─── subscriptions ───────────────────────────────────────────────────────────
CREATE INDEX idx_subscriptions_user_id   ON subscriptions (user_id);
CREATE INDEX idx_subscriptions_plan_id   ON subscriptions (plan_id);
CREATE INDEX idx_subscriptions_status    ON subscriptions (status);
-- partial: only rows that can expire
CREATE INDEX idx_subscriptions_expires_at ON subscriptions (expires_at)
    WHERE expires_at IS NOT NULL;

-- ─── controllers ─────────────────────────────────────────────────────────────
CREATE INDEX idx_controllers_user_id         ON controllers (user_id);
CREATE INDEX idx_controllers_user_bookmaker  ON controllers (user_id, bookmaker);
CREATE INDEX idx_controllers_last_checked_at ON controllers (last_checked_at);
-- partial: only active controllers are polled
CREATE INDEX idx_controllers_active ON controllers (is_active)
    WHERE is_active = true;

-- ─── detected_events ─────────────────────────────────────────────────────────
CREATE INDEX idx_detected_events_controller_id ON detected_events (controller_id);
CREATE INDEX idx_detected_events_detected_at   ON detected_events (detected_at);
-- partial: cleanup job targets rows with expiry
CREATE INDEX idx_detected_events_expires_at ON detected_events (expires_at)
    WHERE expires_at IS NOT NULL;

-- ─── notification_log ────────────────────────────────────────────────────────
CREATE INDEX idx_notification_log_user_id    ON notification_log (user_id);
CREATE INDEX idx_notification_log_event_id   ON notification_log (event_id);
CREATE INDEX idx_notification_log_status     ON notification_log (status);
CREATE INDEX idx_notification_log_created_at ON notification_log (created_at);

-- ─── audit_log ───────────────────────────────────────────────────────────────
CREATE INDEX idx_audit_log_user_id    ON audit_log (user_id);
CREATE INDEX idx_audit_log_action     ON audit_log (action);
CREATE INDEX idx_audit_log_entity     ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
