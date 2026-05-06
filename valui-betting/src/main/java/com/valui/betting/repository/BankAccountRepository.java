package com.valui.betting.repository;

import com.valui.common.entity.BankAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccountEntity, UUID> {

    boolean existsByOwnerTelegramId(Long ownerTelegramId);

    /** Returns the single account for this owner (unique constraint guarantees at most one). */
    Optional<BankAccountEntity> findByOwnerTelegramId(Long ownerTelegramId);

    List<BankAccountEntity> findAllByOwnerTelegramIdInOrderByNameAsc(Collection<Long> ownerTelegramIds);

    @Modifying
    @Query("UPDATE BankAccountEntity a SET a.isDefault = false WHERE a.ownerTelegramId = :tid AND a.id <> :id")
    void clearDefaultExcept(@Param("tid") Long telegramId, @Param("id") UUID id);
}
