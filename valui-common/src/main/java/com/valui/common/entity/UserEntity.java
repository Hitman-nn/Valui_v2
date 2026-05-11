package com.valui.common.entity;

import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.audit.AuditEntityListener;
import com.valui.common.entity.audit.HasUpdatedAt;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@EntityListeners(AuditEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity implements HasUpdatedAt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "language_code", length = 8)
    @Builder.Default
    private String languageCode = "ru";

    /** Текущий баланс токенов. Не сгорает, переходит на следующий месяц. */
    @Column(name = "token_balance", nullable = false)
    @Builder.Default
    private Integer tokenBalance = 0;

    /** Последний абсолютный порог уведомления о балансе (100/50/10 токенов). null = ещё не уведомлялся. Сбрасывается ежемесячно. */
    @Column(name = "token_low_threshold")
    private Integer tokenLowThreshold;

    /** Ежемесячный грант плана — используется для расчёта порогов. Обновляется при начислении гранта. */
    @Column(name = "token_monthly_grant_ref", nullable = false)
    @Builder.Default
    private Integer tokenMonthlyGrantRef = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
