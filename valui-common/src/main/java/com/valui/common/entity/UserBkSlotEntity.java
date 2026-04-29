package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Фиксирует дату первого списания токенов за БК-слот пользователя.
 * Планировщик пропускает слоты, созданные в текущем календарном месяце,
 * так как первый (возможно неполный) месяц уже оплачен при добавлении контроллера.
 */
@Entity
@Table(
    name = "user_bk_slot",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_user_bk_slot",
        columnNames = {"user_id", "bookmaker"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBkSlotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "bookmaker", nullable = false, length = 32)
    private String bookmaker;

    @Column(name = "first_charged_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime firstChargedAt = OffsetDateTime.now();
}
