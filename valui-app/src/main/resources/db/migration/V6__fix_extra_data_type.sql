-- extra_data was jsonb but Hibernate sends String as character varying (type mismatch).
-- text accepts any string including JSON, and Hibernate's String mapping is compatible.
ALTER TABLE detected_events
    ALTER COLUMN extra_data TYPE text USING extra_data::text;
