package com.valui.common.entity;

import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.entity.audit.AuditEntityListener;
import com.valui.common.entity.audit.HasUpdatedAt;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bets")
@EntityListeners(AuditEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BetEntity implements HasUpdatedAt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BetType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BetStatus status = BetStatus.OPEN;

    @Column(name = "total_odds", nullable = false, precision = 10, scale = 4)
    private BigDecimal totalOdds;

    @Column(name = "total_stake", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalStake;

    @Column(name = "potential_payout", nullable = false, precision = 12, scale = 2)
    private BigDecimal potentialPayout;

    @Column(name = "actual_payout", precision = 12, scale = 2)
    private BigDecimal actualPayout;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private BetAccountEntity account;

    @OneToMany(mappedBy = "bet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BetSlipEntity> slips = new ArrayList<>();

    @OneToMany(mappedBy = "bet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BetParticipantEntity> participants = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
