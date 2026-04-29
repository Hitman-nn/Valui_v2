package com.valui.common.entity;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.audit.AuditEntityListener;
import com.valui.common.entity.audit.HasUpdatedAt;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "controllers",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_controllers_user_bookmaker_url",
        columnNames = {"user_id", "bookmaker", "url"}
    )
)
@EntityListeners(AuditEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ControllerEntity implements HasUpdatedAt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "bookmaker", nullable = false, length = 32)
    private BookmakerType bookmaker;

    @Column(name = "url", nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "title", length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ControllerType type;

    @Column(name = "filter_rule", columnDefinition = "text")
    private String filterRule;

    @Column(name = "is_muted", nullable = false)
    @Builder.Default
    private Boolean isMuted = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "poll_interval_sec")
    private Integer pollIntervalSec;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_event_at")
    private OffsetDateTime lastEventAt;

    /** Telegram chat to receive notifications. NULL → user's personal chat (legacy/private). */
    @Column(name = "notification_chat_id")
    private Long notificationChatId;

    /** true когда контроллер приостановлен из-за нехватки токенов. При пополнении восстанавливается. */
    @Column(name = "paused_by_tokens", nullable = false)
    @Builder.Default
    private Boolean pausedByTokens = false;

    /** Дата первой установки filter_rule. Нужна планировщику для пропуска текущего месяца. */
    @Column(name = "filter_set_at")
    private OffsetDateTime filterSetAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
