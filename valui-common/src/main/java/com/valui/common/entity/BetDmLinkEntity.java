package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Explicit opt-in link between a Telegram user's personal chat and a group's betting journal.
 * Created by running /link_bets in the group — lets that user manage the group's bets
 * (create, resolve, print history, manage accounts/persons) from their own DM instead of
 * spamming the group with wizard navigation.
 *
 * One row per (chat, user) — each group member links themselves individually.
 */
@Entity
@Table(name = "bet_dm_links")
@IdClass(BetDmLinkEntity.LinkId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BetDmLinkEntity {

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Id
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    /** Snapshot of the group's title at link time — refreshed on re-link. May go stale if the group is renamed. */
    @Column(name = "chat_title")
    private String chatTitle;

    @Column(name = "linked_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime linkedAt = OffsetDateTime.now();

    @Embeddable
    public record LinkId(Long chatId, Long telegramId) implements java.io.Serializable {}
}
