-- Transactional outbox table for at-least-once Kafka delivery from valui-monitor.
-- Rows are written in the same DB transaction as DetectedEventEntity.
-- OutboxSenderService reads unsent rows every 5 s and publishes them to Kafka.
CREATE TABLE outbox_events (
    id                BIGSERIAL    PRIMARY KEY,
    topic             VARCHAR(64)  NOT NULL,
    message_key       VARCHAR(36)  NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    controller_id     VARCHAR(36)  NOT NULL,
    user_id           VARCHAR(36)  NOT NULL,
    telegram_id       BIGINT,
    bookmaker         VARCHAR(32)  NOT NULL,
    title             VARCHAR(500),
    url               TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    retry_count       INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_outbox_external_event_id
    ON outbox_events (external_event_id);

-- Partial index: only unsent rows are queried by the scheduler
CREATE INDEX idx_outbox_unsent_created
    ON outbox_events (created_at)
    WHERE sent_at IS NULL;
