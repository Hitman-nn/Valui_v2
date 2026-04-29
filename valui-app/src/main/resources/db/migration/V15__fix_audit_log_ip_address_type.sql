-- ip_address was inet; Hibernate sends null as bytea (Types.OTHER fallback) → type mismatch
ALTER TABLE audit_log ALTER COLUMN ip_address TYPE varchar(45) USING ip_address::varchar;
