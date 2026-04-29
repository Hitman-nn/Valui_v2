ALTER TABLE controller_subscriptions ADD COLUMN is_muted BOOLEAN NOT NULL DEFAULT false;

-- Seed is_muted from controllers.is_muted for existing subscriptions
UPDATE controller_subscriptions cs
SET is_muted = COALESCE(c.is_muted, false)
FROM controllers c
WHERE cs.controller_id = c.id;
