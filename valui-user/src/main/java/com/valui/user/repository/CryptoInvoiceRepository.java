package com.valui.user.repository;

import com.valui.common.domain.CryptoInvoiceStatus;
import com.valui.common.entity.CryptoInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CryptoInvoiceRepository extends JpaRepository<CryptoInvoiceEntity, UUID> {

    List<CryptoInvoiceEntity> findAllByStatus(CryptoInvoiceStatus status);

    Optional<CryptoInvoiceEntity> findByInvoiceId(Long invoiceId);

    @Modifying
    @Query("UPDATE CryptoInvoiceEntity i SET i.status = com.valui.common.domain.CryptoInvoiceStatus.EXPIRED " +
           "WHERE i.status = com.valui.common.domain.CryptoInvoiceStatus.PENDING AND i.createdAt < :cutoff")
    int expireOldInvoices(@Param("cutoff") OffsetDateTime cutoff);

    /** Ищет существующий PENDING-инвойс для пользователя и валюты. */
    Optional<CryptoInvoiceEntity> findFirstByUserIdAndCurrencyAndStatus(
        UUID userId, String currency, CryptoInvoiceStatus status);
}
