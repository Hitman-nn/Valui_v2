package com.valui.user.repository;

import com.valui.common.entity.PaymentTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {

    Optional<PaymentTransactionEntity> findByPaymentId(String paymentId);

    @Query("SELECT p FROM PaymentTransactionEntity p WHERE p.user.id = :userId ORDER BY p.createdAt DESC")
    List<PaymentTransactionEntity> findAllByUserId(@Param("userId") UUID userId);
}
