CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── users ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    telegram_id   BIGINT      NOT NULL,
    username      VARCHAR(64),
    first_name    VARCHAR(128),
    role          VARCHAR(20) NOT NULL DEFAULT 'USER',
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    language_code VARCHAR(8)           DEFAULT 'ru',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_telegram_id UNIQUE (telegram_id)
);

-- ─── subscription_plans ──────────────────────────────────────────────────────
CREATE TABLE subscription_plans (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(32)   NOT NULL,
    name               VARCHAR(128)  NOT NULL,
    max_controllers    INT           NOT NULL,
    max_filters        INT           NOT NULL,
    poll_interval_sec  INT           NOT NULL DEFAULT 60,
    allowed_bookmakers TEXT[]        NOT NULL DEFAULT '{}',
    notify_channels    TEXT[]        NOT NULL DEFAULT '{TELEGRAM}',
    price_rub          NUMERIC(10,2) NOT NULL DEFAULT 0,
    is_active          BOOLEAN       NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_subscription_plans_code UNIQUE (code)
);

-- ─── subscriptions ───────────────────────────────────────────────────────────
CREATE TABLE subscriptions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id     UUID        NOT NULL REFERENCES subscription_plans(id),
    status      VARCHAR(20) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    payment_ref VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── controllers ─────────────────────────────────────────────────────────────
CREATE TABLE controllers (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bookmaker         VARCHAR(32) NOT NULL,
    url               TEXT        NOT NULL,
    title             VARCHAR(255),
    type              VARCHAR(20) NOT NULL,
    filter_rule       TEXT,
    is_muted          BOOLEAN     NOT NULL DEFAULT false,
    is_active         BOOLEAN     NOT NULL DEFAULT true,
    poll_interval_sec INT,
    last_checked_at   TIMESTAMPTZ,
    last_event_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_controllers_user_bookmaker_url UNIQUE (user_id, bookmaker, url)
);

-- ─── detected_events ─────────────────────────────────────────────────────────
CREATE TABLE detected_events (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    controller_id     UUID        NOT NULL REFERENCES controllers(id) ON DELETE CASCADE,
    event_external_id VARCHAR(255) NOT NULL,
    title             TEXT        NOT NULL,
    url               TEXT,
    extra_data        JSONB,
    detected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ,
    CONSTRAINT uq_detected_events_controller_external UNIQUE (controller_id, event_external_id)
);

-- ─── notification_log ────────────────────────────────────────────────────────
CREATE TABLE notification_log (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        REFERENCES users(id),
    event_id      UUID        REFERENCES detected_events(id),
    channel       VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    error_message TEXT,
    attempts      INT         NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── audit_log ───────────────────────────────────────────────────────────────
CREATE TABLE audit_log (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         REFERENCES users(id),
    action      VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64),
    entity_id   UUID,
    details     JSONB,
    ip_address  INET,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
