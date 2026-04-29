-- ─── Drop old group-quota tables (слотов в группах НЕТ) ────────────────────
DROP TABLE IF EXISTS group_token_contribution;
DROP TABLE IF EXISTS group_chat_quota;

-- ─── Расширяем subscription_plans: ежемесячный грант и скидка на докупку ───
ALTER TABLE subscription_plans
    ADD COLUMN monthly_token_grant INT NOT NULL DEFAULT 0,
    ADD COLUMN topup_discount_pct  INT NOT NULL DEFAULT 0;

UPDATE subscription_plans SET monthly_token_grant = 0,    topup_discount_pct = 0  WHERE code = 'FREE';
UPDATE subscription_plans SET monthly_token_grant = 200,  topup_discount_pct = 10 WHERE code = 'PRO';
UPDATE subscription_plans SET monthly_token_grant = 1000, topup_discount_pct = 25 WHERE code = 'PREMIUM';

-- ─── Расширяем users: поле для контроля порога уведомлений ─────────────────
-- Хранит % (100=нет, 20=предупреждён при 20%, 10=предупреждён при 10%, 5=предупреждён при 5%)
-- Сбрасывается в 100 при ежемесячном начислении токенов.
ALTER TABLE users
    ADD COLUMN token_low_threshold_pct INT NOT NULL DEFAULT 100,
    ADD COLUMN token_monthly_grant_ref INT NOT NULL DEFAULT 0;

-- ─── Расширяем controllers: флаг приостановки из-за нехватки токенов ───────
ALTER TABLE controllers
    ADD COLUMN paused_by_tokens BOOLEAN NOT NULL DEFAULT false;

-- ─── Стоимость действий (управляется из БД, без перекомпиляции) ─────────────
CREATE TABLE token_action_cost (
    action_code   VARCHAR(64)  PRIMARY KEY,
    cost_tokens   INT          NOT NULL DEFAULT 1,
    description   VARCHAR(255)
);

INSERT INTO token_action_cost (action_code, cost_tokens, description) VALUES
    ('CONTROLLER_BK_MONTHLY',  5, 'Ежемесячная плата за первый контроллер данной БК'),
    ('FILTER_MONTHLY',         2, 'Ежемесячная плата за глобальный фильтр'),
    ('CONTROLLER_FILTER_MONTHLY', 1, 'Ежемесячная плата за фильтр на контроллере'),
    ('NOTIFICATION_SENT',      1, 'Стоимость каждого успешно отправленного уведомления');

-- ─── Пакеты токенов для докупки ─────────────────────────────────────────────
CREATE TABLE token_pack (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(128)  NOT NULL,
    tokens     INT           NOT NULL,
    price_rub  NUMERIC(10,2) NOT NULL,
    is_active  BOOLEAN       NOT NULL DEFAULT true,
    sort_order INT           NOT NULL DEFAULT 0
);

INSERT INTO token_pack (name, tokens, price_rub, sort_order) VALUES
    ('Стартовый',   50,   49.00,  1),
    ('Базовый',    150,  129.00,  2),
    ('Продвинутый', 500,  379.00,  3),
    ('Максимум',  1500,  999.00,  4);

-- ─── Полная история токенных операций ───────────────────────────────────────
CREATE TABLE token_transaction (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delta         INT         NOT NULL,           -- положительный = начисление, отрицательный = списание
    reason_code   VARCHAR(64) NOT NULL,
    ref_id        UUID,                           -- ссылка на контроллер/фильтр/etc
    balance_after INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_token_transaction_user_ts ON token_transaction(user_id, created_at DESC);
