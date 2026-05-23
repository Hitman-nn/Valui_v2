package com.valui.user.repository;

import com.valui.common.entity.CryptoInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CryptoInvoiceRepository extends JpaRepository<CryptoInvoiceEntity, UUID> {

    List<CryptoInvoiceEntity> findAllByStatus(String status);

    Optional<CryptoInvoiceEntity> findByInvoiceId(Long invoiceId);

    /** Все PENDING-инвойсы старше cutoff помечаем как EXPIRED. */
    List<CryptoInvoiceEntity> findAllByStatusAndCreatedAtBefore(String status, OffsetDateTime cutoff);

    /** Ищет существующий PENDING-инвойс для пользователя и валюты. */
    Optional<CryptoInvoiceEntity> findFirstByUserIdAndCurrencyAndStatus(UUID userId, String currency, String status);
}
