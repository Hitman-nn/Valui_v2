package com.valui.notify.log;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLogEntity, UUID> {

    @Modifying
    @Query("UPDATE NotificationLogEntity n SET n.status = 'SENT', n.sentAt = :sentAt, n.attempts = n.attempts + 1 WHERE n.id = :id")
    void markSent(@Param("id") UUID id, @Param("sentAt") OffsetDateTime sentAt);

    @Modifying
    @Query("UPDATE NotificationLogEntity n SET n.status = 'FAILED', n.errorMessage = :msg, n.attempts = n.attempts + 1 WHERE n.id = :id")
    void markFailed(@Param("id") UUID id, @Param("msg") String errorMessage);
}
