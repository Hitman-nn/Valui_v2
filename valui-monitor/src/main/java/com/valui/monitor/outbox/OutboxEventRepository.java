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

    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.sentAt IS NULL
              AND o.createdAt < :cutoff
              AND (o.lockedAt IS NULL OR o.lockedAt < :lockStaleCutoff)
            ORDER BY o.createdAt ASC
            """)
    List<OutboxEvent> findUnsentBefore(@Param("cutoff") OffsetDateTime cutoff,
                                       @Param("lockStaleCutoff") OffsetDateTime lockStaleCutoff);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent o SET o.sentAt = :sentAt, o.retryCount = o.retryCount + 1 WHERE o.id = :id")
    void markSentAt(@Param("id") Long id, @Param("sentAt") OffsetDateTime sentAt);

    /** Atomically claims the row for sending. Returns 1 if claimed, 0 if already locked or sent. */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent o SET o.lockedAt = :now WHERE o.id = :id AND o.sentAt IS NULL AND o.lockedAt IS NULL")
    int tryLock(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
