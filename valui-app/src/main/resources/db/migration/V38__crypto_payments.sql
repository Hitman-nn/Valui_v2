-- Курсы обмена: сколько токенов даётся за 1 единицу криптовалюты
CREATE TABLE token_exchange_rate (
    currency      VARCHAR(16)    PRIMARY KEY,         -- USDT, BTC, ETH, TON
    tokens_per_unit NUMERIC(18,8) NOT NULL DEFAULT 100,
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

INSERT INTO token_exchange_rate (currency, tokens_per_unit) VALUES
    ('USDT', 100),
    ('TON',  20),
    ('ETH',  200000),
    ('BTC',  3000000);

-- Крипто-инвойсы (CryptoBot)
CREATE TABLE crypto_invoice (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invoice_id    BIGINT       NOT NULL UNIQUE,   -- CryptoBot invoice_id
    currency      VARCHAR(16)  NOT NULL,
    crypto_amount NUMERIC(18,8) NOT NULL,
    token_amount  INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING, PAID, EXPIRED
    pay_url       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at       TIMESTAMPTZ
);

CREATE INDEX idx_crypto_invoice_user    ON crypto_invoice(user_id);
CREATE INDEX idx_crypto_invoice_status  ON crypto_invoice(status, created_at);
