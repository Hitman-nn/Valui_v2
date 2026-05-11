-- Remove subscription plan system; keep payment_transactions table for history

DROP TABLE IF EXISTS subscriptions CASCADE;
DROP TABLE IF EXISTS subscription_plans CASCADE;

ALTER TABLE payment_transactions DROP COLUMN IF EXISTS plan_code;
