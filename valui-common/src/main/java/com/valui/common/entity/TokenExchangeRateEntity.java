package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "token_exchange_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenExchangeRateEntity {

    @Id
    @Column(name = "currency", length = 16)
    private String currency;

    /** Количество токенов за 1 единицу данной криптовалюты. */
    @Column(name = "tokens_per_unit", nullable = false, precision = 18, scale = 8)
    private BigDecimal tokensPerUnit;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
