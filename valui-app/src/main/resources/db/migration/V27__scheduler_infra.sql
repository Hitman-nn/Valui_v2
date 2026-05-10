-- ── scheduler_config ─────────────────────────────────────────────────────────
-- Persists MonitorProperties overrides across restarts.
CREATE TABLE scheduler_config (
    key        VARCHAR(100) PRIMARY KEY,
    value      VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── poll_history ──────────────────────────────────────────────────────────────
-- Per-controller poll execution log; replaces Redis-only storage.
-- Kept for 7 days (purged by scheduled job), enables hourly aggregation analytics.
CREATE TABLE poll_history (
    id            BIGSERIAL    PRIMARY KEY,
    controller_id UUID         NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL,
    duration_ms   BIGINT       NOT NULL,
    events_found  INTEGER      NOT NULL,   -- -1 = error/timeout
    status        VARCHAR(20)  NOT NULL    -- 'ok' | 'error' | 'timeout'
);

CREATE INDEX idx_poll_history_ctrl_started
    ON poll_history (controller_id, started_at DESC);

CREATE INDEX idx_poll_history_started
    ON poll_history (started_at DESC);

-- ── shedlock ─────────────────────────────────────────────────────────────────
-- Required by ShedLock library for distributed-leader election.
-- Schema pre-created so enabling horizontal scaling later is a one-liner.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
