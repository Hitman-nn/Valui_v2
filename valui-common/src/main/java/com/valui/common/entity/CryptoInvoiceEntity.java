package com.valui.common.entity;

import com.valui.common.domain.CryptoInvoiceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "crypto_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CryptoInvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    /** CryptoBot invoice_id. */
    @Column(name = "invoice_id", nullable = false, unique = true)
    private Long invoiceId;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "crypto_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal cryptoAmount;

    @Column(name = "token_amount", nullable = false)
    private Integer tokenAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private CryptoInvoiceStatus status = CryptoInvoiceStatus.PENDING;

    @Column(name = "pay_url", nullable = false, columnDefinition = "text")
    private String payUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;
}
