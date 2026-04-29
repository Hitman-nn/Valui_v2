package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "token_pack")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenPackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "tokens", nullable = false)
    private Integer tokens;

    @Column(name = "price_rub", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceRub;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
