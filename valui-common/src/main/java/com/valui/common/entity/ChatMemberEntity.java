package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Entity
@Table(name = "chat_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ChatMemberEntity.PK.class)
public class ChatMemberEntity {

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Id
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "seen_at", nullable = false)
    private OffsetDateTime seenAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long chatId;
        private Long telegramId;
    }
}
