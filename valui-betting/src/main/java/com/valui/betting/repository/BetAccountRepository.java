package com.valui.betting.repository;

import com.valui.common.entity.BetAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetAccountRepository extends JpaRepository<BetAccountEntity, UUID> {
    List<BetAccountEntity> findAllByChatIdOrderByNameAsc(Long chatId);
    boolean existsByChatIdAndNameIgnoreCase(Long chatId, String name);

    @Query("SELECT DISTINCT b.account FROM BetEntity b WHERE b.telegramId = :tid AND b.account IS NOT NULL ORDER BY b.account.name ASC")
    List<BetAccountEntity> findAccountsByTelegramId(@Param("tid") Long telegramId);
}
