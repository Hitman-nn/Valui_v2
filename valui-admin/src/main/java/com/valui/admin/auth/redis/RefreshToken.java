package com.valui.admin.auth.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

/**
 * Refresh token stored in Redis with 30-day TTL.
 * Key pattern: {@code refresh_token:{id}}
 */
@RedisHash(value = "refresh_token", timeToLive = 2_592_000L)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    private String id;          // the token value (UUID)

    private String userId;      // UUID as String
    private Long telegramId;
    private String role;
}
