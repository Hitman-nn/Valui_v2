package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tracks the maximum number of controllers allowed in a Telegram group chat.
 * Quota = 3 (free baseline) + SUM(tokens committed by members).
 */
@Entity
@Table(name = "group_chat_quota")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupChatQuotaEntity {

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "max_controllers", nullable = false)
    @Builder.Default
    private Integer maxControllers = 3;
}
