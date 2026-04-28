package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Tracks how many tokens a user has committed to expand a group chat's controller quota.
 * Revoked (set to 0 and quota adjusted) when the user's paid subscription expires.
 */
@Entity
@Table(
    name = "group_token_contribution",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_group_token_contribution",
        columnNames = {"chat_id", "user_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTokenContributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "tokens_committed", nullable = false)
    @Builder.Default
    private Integer tokensCommitted = 0;
}
