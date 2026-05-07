package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "bet_participants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BetParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bet_id", nullable = false)
    private BetEntity bet;

    /** Nullable — new bets use person instead */
    @Column(name = "telegram_id")
    private Long telegramId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private BetPersonEntity person;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "stake", nullable = false, precision = 12, scale = 2)
    private BigDecimal stake;

    /** Share of P&L: 0.5 = 50%. All participants must sum to 1.0. */
    @Column(name = "profit_share", nullable = false, precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal profitShare = BigDecimal.ONE;
}
