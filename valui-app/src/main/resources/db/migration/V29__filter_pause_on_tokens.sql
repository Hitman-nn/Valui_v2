-- Pause global filters and controller filters instead of deleting them on token shortage

ALTER TABLE global_filters
    ADD COLUMN paused_by_tokens BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE controllers
    ADD COLUMN filter_paused_by_tokens BOOLEAN NOT NULL DEFAULT false;
