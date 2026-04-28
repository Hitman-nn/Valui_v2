-- ─── audit_fallback ──────────────────────────────────────────────────────────
-- Stores audit events that could not be delivered to Kafka.
-- A replay job can drain this table back into audit.log once the broker recovers.

CREATE TABLE audit_fallback (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    telegram_id BIGINT,
    action      VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64),
    entity_id   UUID,
    details     JSONB,
    ip_address  INET,
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_fallback_created_at ON audit_fallback (created_at DESC);
CREATE INDEX idx_audit_fallback_user_id    ON audit_fallback (user_id) WHERE user_id IS NOT NULL;
