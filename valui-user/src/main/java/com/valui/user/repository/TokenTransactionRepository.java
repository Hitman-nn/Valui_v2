package com.valui.user.repository;

import com.valui.common.entity.TokenTransactionEntity;
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
public interface TokenTransactionRepository extends JpaRepository<TokenTransactionEntity, UUID> {

    Page<TokenTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<TokenTransactionEntity> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<TokenTransactionEntity> findTop50ByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        UUID userId, OffsetDateTime from);

    @Query("SELECT COALESCE(SUM(t.delta), 0L) FROM TokenTransactionEntity t " +
           "WHERE t.user.id = :userId AND t.delta < 0 AND t.createdAt >= :from")
    long sumSpentFrom(@Param("userId") UUID userId, @Param("from") OffsetDateTime from);

    @Query("SELECT COALESCE(SUM(t.delta), 0L) FROM TokenTransactionEntity t " +
           "WHERE t.user.id = :userId AND t.delta < 0 AND t.createdAt >= :from AND t.createdAt < :to")
    long sumSpentInPeriod(@Param("userId") UUID userId,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to);

    @Query("SELECT COALESCE(SUM(t.delta), 0L) FROM TokenTransactionEntity t " +
           "WHERE t.user.id = :userId AND t.delta < 0")
    long sumSpentTotal(@Param("userId") UUID userId);

    @Query("SELECT MIN(t.createdAt) FROM TokenTransactionEntity t WHERE t.user.id = :userId")
    Optional<OffsetDateTime> findEarliestCreatedAt(@Param("userId") UUID userId);
}
