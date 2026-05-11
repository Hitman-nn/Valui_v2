-- Переименовываем колонку: порог теперь абсолютный (токены), не процентный
ALTER TABLE users RENAME COLUMN token_low_threshold_pct TO token_low_threshold;
ALTER TABLE users ALTER COLUMN token_low_threshold DROP NOT NULL;
ALTER TABLE users ALTER COLUMN token_low_threshold DROP DEFAULT;
UPDATE users SET token_low_threshold = NULL;
