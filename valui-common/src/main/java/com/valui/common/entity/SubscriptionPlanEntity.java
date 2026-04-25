package com.valui.common.entity;

import com.valui.common.entity.audit.AuditEntityListener;
import com.valui.common.entity.audit.HasCreatedAt;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription_plans")
@EntityListeners(AuditEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanEntity implements HasCreatedAt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "max_controllers", nullable = false)
    private Integer maxControllers;

    @Column(name = "max_filters", nullable = false)
    private Integer maxFilters;

    @Column(name = "poll_interval_sec", nullable = false)
    @Builder.Default
    private Integer pollIntervalSec = 60;

    @Column(name = "allowed_bookmakers", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] allowedBookmakers = new String[]{};

    @Column(name = "notify_channels", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private String[] notifyChannels = new String[]{"TELEGRAM"};

    @Column(name = "price_rub", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal priceRub = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
