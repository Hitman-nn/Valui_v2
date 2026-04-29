package com.valui.monitor.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findAllByExternalEventIdAndSentAtIsNull(String externalEventId);

    @Query("SELECT o FROM OutboxEvent o WHERE o.sentAt IS NULL AND o.createdAt < :cutoff ORDER BY o.createdAt ASC")
    List<OutboxEvent> findUnsentBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent o SET o.sentAt = :sentAt, o.retryCount = o.retryCount + 1 WHERE o.id = :id")
    void markSentAt(@Param("id") Long id, @Param("sentAt") OffsetDateTime sentAt);
}
