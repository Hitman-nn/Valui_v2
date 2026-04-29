package com.valui.user.repository;

import com.valui.common.entity.TokenTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TokenTransactionRepository extends JpaRepository<TokenTransactionEntity, UUID> {

    Page<TokenTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
