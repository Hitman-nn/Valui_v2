-- Отслеживает первое списание токенов за БК-слот (первый контроллер данной БК у пользователя).
-- Используется планировщиком: если first_charged_at в текущем месяце — пропускаем,
-- т.к. первый месяц уже оплачен при добавлении (может быть неполным).
CREATE TABLE user_bk_slot (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bookmaker        VARCHAR(32) NOT NULL,
    first_charged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_bk_slot UNIQUE (user_id, bookmaker)
);

CREATE INDEX idx_user_bk_slot_user ON user_bk_slot(user_id);

-- Дата первой установки filter_rule на контроллер.
-- Планировщик пропускает контроллеры, где filter_set_at в текущем месяце.
ALTER TABLE controllers
    ADD COLUMN filter_set_at TIMESTAMPTZ;
