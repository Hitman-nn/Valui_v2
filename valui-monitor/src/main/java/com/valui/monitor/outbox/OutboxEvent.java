package com.valui.monitor.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(name = "idx_outbox_unsent_created", columnList = "created_at"),
                @Index(name = "uq_outbox_event_chat", columnList = "external_event_id,chat_id", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 36)
    private String messageKey;

    @Column(name = "external_event_id", nullable = false, length = 255)
    private String externalEventId;

    @Column(name = "controller_id", nullable = false, length = 36)
    private String controllerId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(nullable = false, length = 32)
    private String bookmaker;

    @Column(length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String url;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;
}
