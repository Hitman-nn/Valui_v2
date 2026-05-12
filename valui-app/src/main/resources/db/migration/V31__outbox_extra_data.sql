ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS extra_data TEXT;
