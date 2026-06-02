package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "market_watch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketWatchEntity {

    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_FIRED   = "FIRED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "telegram_id", nullable = false)
    private long telegramId;

    @Column(name = "controller_id", nullable = false)
    private UUID controllerId;

    @Column(name = "external_event_id", nullable = false, length = 100)
    private String externalEventId;

    @Column(name = "bookmaker", nullable = false, length = 50)
    private String bookmaker;

    // 'HCAP' | 'TOTAL'
    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType;

    @Column(name = "match_title", length = 500)
    private String matchTitle;

    @Column(name = "match_url", length = 1000)
    private String matchUrl;

    @Column(name = "message_id")
    private Integer messageId;

    @Column(name = "notif_log_id")
    private UUID notifLogId;

    @Column(name = "start_epoch")
    private Long startEpoch;

    // 'ACTIVE' | 'FIRED' | 'EXPIRED'
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
