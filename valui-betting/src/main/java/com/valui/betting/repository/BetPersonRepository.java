package com.valui.betting.repository;

import com.valui.common.entity.BetPersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetPersonRepository extends JpaRepository<BetPersonEntity, UUID> {
    List<BetPersonEntity> findAllByChatIdOrderByDisplayNameAsc(Long chatId);
    boolean existsByChatIdAndDisplayNameIgnoreCase(Long chatId, String displayName);

    List<BetPersonEntity> findAllByOrderByDisplayNameAsc();

    @Query("""
           SELECT DISTINCT p.person FROM BetParticipantEntity p
           JOIN p.bet b
           WHERE b.account.id IN :accountIds AND p.person IS NOT NULL
           ORDER BY p.person.displayName ASC
           """)
    List<BetPersonEntity> findPersonsByAccountIds(@Param("accountIds") List<UUID> accountIds);
}
