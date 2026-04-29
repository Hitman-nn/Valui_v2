CREATE TABLE controller_subscriptions (
    controller_id    UUID        NOT NULL REFERENCES controllers(id) ON DELETE CASCADE,
    chat_id          BIGINT      NOT NULL,
    user_id          UUID        NOT NULL REFERENCES users(id),
    telegram_id      BIGINT      NOT NULL,
    paused_by_tokens BOOLEAN     NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (controller_id, chat_id)
);

CREATE INDEX idx_ctrl_subs_user_id ON controller_subscriptions(user_id);

INSERT INTO controller_subscriptions (controller_id, chat_id, user_id, telegram_id, paused_by_tokens)
SELECT
    c.id,
    COALESCE(c.notification_chat_id, u.telegram_id),
    c.user_id,
    u.telegram_id,
    COALESCE(c.paused_by_tokens, false)
FROM controllers c
JOIN users u ON u.id = c.user_id
WHERE c.is_active = true
ON CONFLICT DO NOTHING;

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS chat_id BIGINT;
UPDATE outbox_events SET chat_id = telegram_id WHERE chat_id IS NULL;
DROP INDEX IF EXISTS uq_outbox_external_event_id;
CREATE UNIQUE INDEX uq_outbox_event_chat ON outbox_events(external_event_id, chat_id);
