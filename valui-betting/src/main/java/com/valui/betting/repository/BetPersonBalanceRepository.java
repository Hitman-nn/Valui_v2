package com.valui.betting.repository;

import com.valui.common.entity.BetPersonBalanceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BetPersonBalanceRepository extends JpaRepository<BetPersonBalanceEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BetPersonBalanceEntity> findByAccountIdAndPersonId(UUID accountId, UUID personId);

    @Query("SELECT b FROM BetPersonBalanceEntity b JOIN FETCH b.person WHERE b.account.id = :accountId ORDER BY b.person.displayName")
    List<BetPersonBalanceEntity> findByAccountIdWithPerson(@Param("accountId") UUID accountId);

    void deleteByAccountIdAndPersonId(UUID accountId, UUID personId);

    @Query("SELECT COALESCE(SUM(b.balance), 0) FROM BetPersonBalanceEntity b WHERE b.account.id = :accountId")
    java.math.BigDecimal sumBalanceByAccountId(@Param("accountId") UUID accountId);
}
