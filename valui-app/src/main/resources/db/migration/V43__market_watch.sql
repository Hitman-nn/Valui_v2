-- Watches set by users to track the appearance of a handicap or total market
-- for a specific match in a specific bookmaker.
-- market_type: 'HCAP' | 'TOTAL'
-- status:      'ACTIVE' | 'FIRED' | 'EXPIRED'

CREATE TABLE market_watch (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chat_id           BIGINT       NOT NULL,
    telegram_id       BIGINT       NOT NULL,
    controller_id     UUID         NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    bookmaker         VARCHAR(50)  NOT NULL,
    market_type       VARCHAR(10)  NOT NULL,
    match_title       VARCHAR(500),
    match_url         VARCHAR(1000),
    message_id        INTEGER,
    notif_log_id      UUID,
    start_epoch       BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Prevent duplicate active watches for the same (user, match, market)
CREATE UNIQUE INDEX idx_market_watch_unique
    ON market_watch (chat_id, external_event_id, bookmaker, market_type)
    WHERE status = 'ACTIVE';

-- Fast lookup during controller polls
CREATE INDEX idx_market_watch_ctrl_active
    ON market_watch (controller_id)
    WHERE status = 'ACTIVE';

-- Fast expiration scan
CREATE INDEX idx_market_watch_start_active
    ON market_watch (start_epoch)
    WHERE status = 'ACTIVE';
