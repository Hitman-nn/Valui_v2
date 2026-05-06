package com.valui.betting.repository;

import com.valui.common.domain.BetStatus;
import com.valui.common.entity.BetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BetRepository extends JpaRepository<BetEntity, UUID> {

    @EntityGraph(attributePaths = {"slips", "participants", "participants.bankAccount"})
    Optional<BetEntity> findWithDetailById(UUID id);

    Page<BetEntity> findAllByTelegramIdOrderByCreatedAtDesc(Long telegramId, Pageable pageable);

    Page<BetEntity> findAllByTelegramIdAndStatusOrderByCreatedAtDesc(Long telegramId, BetStatus status, Pageable pageable);

    Page<BetEntity> findAllByChatIdOrderByCreatedAtDesc(Long chatId, Pageable pageable);

    long countByTelegramIdAndStatus(Long telegramId, BetStatus status);

    @Query("""
            SELECT COALESCE(SUM(b.totalStake), 0)
            FROM BetEntity b
            WHERE b.telegramId = :tid
              AND b.status <> 'CANCELLED'
            """)
    java.math.BigDecimal sumTotalStakeByTelegramId(@Param("tid") Long telegramId);

    @Query("""
            SELECT COALESCE(SUM(b.actualPayout), 0)
            FROM BetEntity b
            WHERE b.telegramId = :tid
              AND b.status IN ('WON', 'RETURNED')
            """)
    java.math.BigDecimal sumActualPayoutByTelegramId(@Param("tid") Long telegramId);
}
