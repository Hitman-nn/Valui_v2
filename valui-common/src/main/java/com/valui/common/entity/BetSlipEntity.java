package com.valui.common.entity;

import com.valui.common.domain.SlipResult;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "bet_slips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BetSlipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bet_id", nullable = false)
    private BetEntity bet;

    @Column(name = "match_title", nullable = false, columnDefinition = "text")
    private String matchTitle;

    @Column(name = "match_url", columnDefinition = "text")
    private String matchUrl;

    @Column(name = "bookmaker", length = 32)
    private String bookmaker;

    @Column(name = "odds", nullable = false, precision = 8, scale = 4)
    private BigDecimal odds;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    @Builder.Default
    private SlipResult result = SlipResult.OPEN;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
