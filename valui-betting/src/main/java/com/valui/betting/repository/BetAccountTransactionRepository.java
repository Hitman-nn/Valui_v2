package com.valui.betting.repository;

import com.valui.common.entity.BetAccountTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetAccountTransactionRepository extends JpaRepository<BetAccountTransactionEntity, UUID> {

    @Query("""
           SELECT t FROM BetAccountTransactionEntity t JOIN FETCH t.person
           WHERE t.account.id = :accountId AND t.person.id = :personId
           ORDER BY t.createdAt ASC
           """)
    List<BetAccountTransactionEntity> findByAccountAndPerson(
            @Param("accountId") UUID accountId,
            @Param("personId")  UUID personId);
}
