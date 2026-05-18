package com.valui.bot.service;

import com.valui.bot.keyboard.menu.ControllerMenuBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Persists the per-user controller list sort preference in Redis.
 * Key: {@code bot:prefs:ctrl-sort:{chatId}}, TTL: 90 days.
 */
@Service
@RequiredArgsConstructor
public class ControllerSortPreferenceService {

    private static final String   KEY_PREFIX = "bot:prefs:ctrl-sort:";
    private static final Duration TTL        = Duration.ofDays(90);

    private final StringRedisTemplate stringRedisTemplate;

    /** Returns {@link ControllerMenuBuilder#SORT_NAME} or {@link ControllerMenuBuilder#SORT_DATE} (default). */
    public String load(long chatId) {
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + chatId);
        return ControllerMenuBuilder.SORT_NAME.equals(value)
                ? ControllerMenuBuilder.SORT_NAME
                : ControllerMenuBuilder.SORT_DATE;
    }

    public void save(long chatId, String sort) {
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + chatId, sort, TTL);
    }
}
