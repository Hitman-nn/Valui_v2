package com.valui.betting.repository;

import com.valui.common.entity.BetParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetParticipantRepository extends JpaRepository<BetParticipantEntity, UUID> {

    List<BetParticipantEntity> findAllByBetId(UUID betId);

    boolean existsByTelegramIdAndBet_ChatId(Long telegramId, Long chatId);

    @Query("SELECT DISTINCT b.chatId FROM BetParticipantEntity p JOIN p.bet b WHERE p.telegramId = :tid ORDER BY b.chatId")
    List<Long> findDistinctChatIdsByTelegramId(@Param("tid") Long telegramId);

    /**
     * Returns distinct (telegramId, displayName) pairs for all participants in bets
     * placed in the given chat. Uses MAX(displayName) to pick any non-null name per ID.
     * Result: Object[0] = Long telegramId, Object[1] = String displayName (may be null).
     */
    @Query("""
            SELECT p.telegramId, MAX(p.displayName)
            FROM BetParticipantEntity p
            JOIN p.bet b
            WHERE b.chatId = :chatId
            GROUP BY p.telegramId
            ORDER BY MAX(p.displayName) ASC NULLS LAST
            """)
    List<Object[]> findDistinctParticipantsByChatId(@Param("chatId") Long chatId);
}
