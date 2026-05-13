ALTER TABLE bet_slips
    ADD COLUMN starts_at            TIMESTAMPTZ,
    ADD COLUMN initial_extra_data   TEXT,
    ADD COLUMN snapshot_extra_data  TEXT,
    ADD COLUMN snapshot_taken_at    TIMESTAMPTZ;
