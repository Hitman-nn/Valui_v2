package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "controller_subscriptions")
@IdClass(ControllerSubscriptionEntity.SubscriptionId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ControllerSubscriptionEntity {

    @Id
    @Column(name = "controller_id", nullable = false)
    private UUID controllerId;

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "is_muted", nullable = false)
    @Builder.Default
    private boolean isMuted = false;

    @Column(name = "paused_by_tokens", nullable = false)
    @Builder.Default
    private boolean pausedByTokens = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Embeddable
    public record SubscriptionId(UUID controllerId, Long chatId) implements java.io.Serializable {}
}
