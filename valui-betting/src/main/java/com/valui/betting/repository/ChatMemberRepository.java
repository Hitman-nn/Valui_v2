package com.valui.betting.repository;

import com.valui.common.entity.ChatMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface ChatMemberRepository extends JpaRepository<ChatMemberEntity, ChatMemberEntity.PK> {

    List<ChatMemberEntity> findAllByChatIdOrderByFirstNameAsc(Long chatId);

    @Modifying
    @Query(value = """
            INSERT INTO chat_members (chat_id, telegram_id, first_name, username, seen_at)
            VALUES (:chatId, :telegramId, :firstName, :username, :seenAt)
            ON CONFLICT (chat_id, telegram_id) DO UPDATE
              SET first_name = EXCLUDED.first_name,
                  username   = EXCLUDED.username,
                  seen_at    = EXCLUDED.seen_at
            """,
            nativeQuery = true)
    void upsert(
            @Param("chatId")     Long chatId,
            @Param("telegramId") Long telegramId,
            @Param("firstName")  String firstName,
            @Param("username")   String username,
            @Param("seenAt")     OffsetDateTime seenAt
    );
}
