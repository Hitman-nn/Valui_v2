-- notification_log.event_id: изменить FK на ON DELETE SET NULL,
-- чтобы удаление события (например, при resend) сохраняло историю уведомлений.
ALTER TABLE notification_log
    DROP CONSTRAINT notification_log_event_id_fkey,
    ADD CONSTRAINT notification_log_event_id_fkey
        FOREIGN KEY (event_id) REFERENCES detected_events(id)
        ON DELETE SET NULL;
