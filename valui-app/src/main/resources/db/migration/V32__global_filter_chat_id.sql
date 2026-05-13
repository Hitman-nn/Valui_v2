-- Привязываем глобальные фильтры к чату, в котором они были созданы.
-- Существующим фильтрам проставляем telegram_id владельца (личный чат).
ALTER TABLE global_filters ADD COLUMN chat_id BIGINT;

UPDATE global_filters gf
    SET chat_id = u.telegram_id
    FROM users u
    WHERE gf.user_id = u.id;

ALTER TABLE global_filters ALTER COLUMN chat_id SET NOT NULL;

CREATE INDEX idx_global_filters_chat_id ON global_filters(chat_id);
