-- V22: track users seen in each chat for the betting journal participant picker
CREATE TABLE chat_members (
    chat_id      BIGINT       NOT NULL,
    telegram_id  BIGINT       NOT NULL,
    first_name   VARCHAR(128),
    username     VARCHAR(64),
    seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, telegram_id)
);

CREATE INDEX idx_chat_members_chat_id ON chat_members(chat_id);
