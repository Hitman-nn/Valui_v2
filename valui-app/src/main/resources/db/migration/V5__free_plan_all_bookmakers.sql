-- Expand FREE plan to include all available bookmakers.
-- Without this, the wizard shows only 2 bookmakers and developers cannot test the full flow.
UPDATE subscription_plans
SET allowed_bookmakers = '{XBET,FONBET,OLIMP,BETCITY,BETBOOM}'
WHERE code = 'FREE';
