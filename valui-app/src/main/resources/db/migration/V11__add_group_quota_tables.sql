-- Extra controller slots purchased via paid plans (converted to group tokens).
ALTER TABLE users
    ADD COLUMN token_balance INT NOT NULL DEFAULT 0;

-- Per-group ceiling: 3 free + committed tokens from members.
CREATE TABLE group_chat_quota (
    chat_id         BIGINT PRIMARY KEY,
    max_controllers INT    NOT NULL DEFAULT 3
);

-- Tracks how many tokens each user has committed to a group (to expand its quota).
-- Revoked when the user's paid subscription expires.
CREATE TABLE group_token_contribution (
    id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id          BIGINT  NOT NULL,
    user_id          UUID    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tokens_committed INT     NOT NULL DEFAULT 0,
    CONSTRAINT uq_group_token_contribution UNIQUE (chat_id, user_id)
);

CREATE INDEX idx_group_token_contribution_user ON group_token_contribution(user_id);
