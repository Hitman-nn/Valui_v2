CREATE TABLE payment_transactions (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL REFERENCES users(id),
    payment_id       VARCHAR(255)  NOT NULL,
    plan_code        VARCHAR(32)   NOT NULL,
    amount           NUMERIC(10,2) NOT NULL,
    currency         VARCHAR(8)    NOT NULL DEFAULT 'RUB',
    status           VARCHAR(20)   NOT NULL,
    description      TEXT,
    confirmation_url TEXT,
    gateway          VARCHAR(32)   NOT NULL DEFAULT 'stub',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_transactions_payment_id UNIQUE (payment_id)
);

CREATE INDEX idx_payment_transactions_user_id    ON payment_transactions (user_id);
CREATE INDEX idx_payment_transactions_status     ON payment_transactions (status);
CREATE INDEX idx_payment_transactions_created_at ON payment_transactions (created_at);
