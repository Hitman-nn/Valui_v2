ALTER TABLE subscription_plans
    ADD COLUMN token_reward INT NOT NULL DEFAULT 0;

UPDATE subscription_plans SET token_reward = 0   WHERE code = 'FREE';
UPDATE subscription_plans SET token_reward = 50  WHERE code = 'PRO';
UPDATE subscription_plans SET token_reward = 300 WHERE code = 'PREMIUM';
