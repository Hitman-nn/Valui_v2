-- Дата сброса статистики токенов. Если задана — история и статистика
-- отображаются только начиная с этой даты. Данные не удаляются.
ALTER TABLE users ADD COLUMN token_stats_reset_at TIMESTAMPTZ NULL;
