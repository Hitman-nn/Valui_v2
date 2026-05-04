package com.valui.user.repository;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.entity.SubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {

    Optional<SubscriptionEntity> findTopByUserIdAndStatusOrderByStartedAtDesc(UUID userId, SubscriptionStatus status);

    List<SubscriptionEntity> findAllByUserId(UUID userId);

    @Query("""
            SELECT s FROM SubscriptionEntity s
            WHERE s.status = 'ACTIVE'
              AND s.expiresAt IS NOT NULL
              AND s.expiresAt < :threshold
            """)
    List<SubscriptionEntity> findExpiredBefore(@Param("threshold") OffsetDateTime threshold);

    @Query(value = "SELECT s FROM SubscriptionEntity s JOIN FETCH s.plan JOIN FETCH s.user WHERE s.status = 'ACTIVE'",
           countQuery = "SELECT COUNT(s) FROM SubscriptionEntity s WHERE s.status = 'ACTIVE'")
    Page<SubscriptionEntity> findAllActiveWithDetails(Pageable pageable);

    @Query("""
            SELECT s FROM SubscriptionEntity s JOIN FETCH s.plan JOIN FETCH s.user
            WHERE s.status = 'ACTIVE'
              AND s.expiresAt IS NOT NULL
              AND s.expiresAt BETWEEN :from AND :to
            ORDER BY s.expiresAt ASC
            """)
    List<SubscriptionEntity> findExpiringBetween(
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Query("""
            SELECT sp.code, sp.name, COUNT(s)
            FROM SubscriptionEntity s JOIN s.plan sp
            WHERE s.status = 'ACTIVE'
            GROUP BY sp.id, sp.code, sp.name
            ORDER BY COUNT(s) DESC
            """)
    List<Object[]> countActiveGroupedByPlan();

    @Query("""
            SELECT u.telegramId
            FROM SubscriptionEntity s JOIN s.user u JOIN s.plan sp
            WHERE s.status = 'ACTIVE'
              AND (:planCode IS NULL OR sp.code = :planCode)
            """)
    List<Long> findActiveTelegramIdsByPlan(@Param("planCode") String planCode);
}
