package com.valui.betting.repository;

import com.valui.common.domain.BetStatus;
import com.valui.common.entity.BetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BetRepository extends JpaRepository<BetEntity, UUID> {

    @Query("SELECT DISTINCT b FROM BetEntity b LEFT JOIN FETCH b.slips WHERE b.id = :id")
    Optional<BetEntity> findWithSlipsById(@Param("id") UUID id);

    @Query("SELECT DISTINCT b FROM BetEntity b LEFT JOIN FETCH b.participants p LEFT JOIN FETCH p.person WHERE b.id = :id")
    Optional<BetEntity> findWithParticipantsById(@Param("id") UUID id);

    // Two queries to avoid MultipleBagFetchException. Both run in the same L1 cache:
    // first primes the slips collection, second primes participants and is returned.
    default Optional<BetEntity> findWithDetailById(UUID id) {
        findWithSlipsById(id);
        return findWithParticipantsById(id);
    }

    Page<BetEntity> findAllByChatIdOrderByCreatedAtDesc(Long chatId, Pageable pageable);

    Page<BetEntity> findAllByChatIdAndStatusOrderByCreatedAtDesc(Long chatId, BetStatus status, Pageable pageable);

    long countByChatIdAndStatus(Long chatId, BetStatus status);

    @Query("SELECT COALESCE(SUM(b.totalStake), 0) FROM BetEntity b WHERE b.chatId = :chatId AND b.status <> 'CANCELLED'")
    BigDecimal sumTotalStakeByChatId(@Param("chatId") Long chatId);

    @Query("SELECT COALESCE(SUM(b.actualPayout), 0) FROM BetEntity b WHERE b.chatId = :chatId AND b.status IN ('WON', 'RETURNED')")
    BigDecimal sumActualPayoutByChatId(@Param("chatId") Long chatId);

    /**
     * Returns per-status counts and aggregated staked/payout totals in a single query,
     * replacing 7 separate count/sum calls in getStats().
     */
    @Query("""
            SELECT b.status         as status,
                   COUNT(b)         as cnt,
                   COALESCE(SUM(CASE WHEN b.status <> com.valui.common.domain.BetStatus.CANCELLED THEN b.totalStake  ELSE 0 END), 0) as totalStake,
                   COALESCE(SUM(CASE WHEN b.status IN (com.valui.common.domain.BetStatus.WON, com.valui.common.domain.BetStatus.RETURNED) THEN b.actualPayout ELSE 0 END), 0) as totalPayout
            FROM BetEntity b
            WHERE b.chatId = :chatId
            GROUP BY b.status
            """)
    List<BetStatRow> aggregateStatsByChatId(@Param("chatId") Long chatId);

    interface BetStatRow {
        BetStatus getStatus();
        long getCnt();
        java.math.BigDecimal getTotalStake();
        java.math.BigDecimal getTotalPayout();
    }

    // ── Account-scoped queries ────────────────────────────────────────────────

    long countByAccount_IdAndStatus(UUID accountId, BetStatus status);

    @Query("SELECT COALESCE(SUM(b.totalStake), 0) FROM BetEntity b WHERE b.account.id = :aid AND b.status <> 'CANCELLED'")
    BigDecimal sumTotalStakeByAccountId(@Param("aid") UUID accountId);

    @Query("SELECT COALESCE(SUM(b.actualPayout), 0) FROM BetEntity b WHERE b.account.id = :aid AND b.status IN ('WON', 'RETURNED')")
    BigDecimal sumActualPayoutByAccountId(@Param("aid") UUID accountId);

    // ── Person-scoped queries ─────────────────────────────────────────────────

    @Query("SELECT COUNT(DISTINCT b) FROM BetEntity b JOIN b.participants p WHERE p.person.id = :pid AND b.chatId = :chatId AND b.status = :status")
    long countByPersonIdAndChatIdAndStatus(@Param("pid") UUID personId, @Param("chatId") Long chatId, @Param("status") BetStatus status);

    @Query("SELECT COALESCE(SUM(p.stake), 0) FROM BetParticipantEntity p WHERE p.person.id = :pid AND p.bet.chatId = :chatId AND p.bet.status <> 'CANCELLED'")
    BigDecimal sumStakeByPersonId(@Param("pid") UUID personId, @Param("chatId") Long chatId);

    @Query("SELECT COALESCE(SUM(p.profitShare * b.actualPayout), 0) FROM BetParticipantEntity p JOIN p.bet b WHERE p.person.id = :pid AND b.chatId = :chatId AND b.status IN ('WON', 'RETURNED')")
    BigDecimal sumPayoutByPersonId(@Param("pid") UUID personId, @Param("chatId") Long chatId);
}
