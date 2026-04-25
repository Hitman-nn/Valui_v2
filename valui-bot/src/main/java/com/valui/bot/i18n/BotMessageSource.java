package com.valui.bot.i18n;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Bot-aware message source that:
 * <ul>
 *   <li>Resolves the user's locale from DB ({@link BotLocaleResolver})</li>
 *   <li>Caches the resolved locale tag in Redis for 1 hour</li>
 *   <li>Delegates text retrieval to Spring's {@link MessageSource}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotMessageSource {

    static final Duration LOCALE_CACHE_TTL = Duration.ofHours(1);
    static final String   CACHE_KEY_PREFIX = "bot:locale:";

    private final MessageSource       messageSource;
    private final BotLocaleResolver   localeResolver;
    private final StringRedisTemplate redisTemplate;

    /** Returns the localized message for {@code key} in the user's language. */
    public String getMessage(String key, Long chatId) {
        return messageSource.getMessage(key, null, resolveLocale(chatId));
    }

    /** Returns the localized message with positional arguments ({0}, {1}, …). */
    public String getMessage(String key, Long chatId, Object... args) {
        return messageSource.getMessage(key, args, resolveLocale(chatId));
    }

    /**
     * Removes the cached locale for {@code chatId}.
     * Call this after the user changes their language preference.
     */
    public void invalidateLocaleCache(Long chatId) {
        redisTemplate.delete(CACHE_KEY_PREFIX + chatId);
        log.debug("Locale cache invalidated for chatId={}", chatId);
    }

    // ─── private ─────────────────────────────────────────────────────────────

    Locale resolveLocale(Long chatId) {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + chatId);
        if (cached != null) {
            return Locale.forLanguageTag(cached);
        }

        Locale locale = resolveFromDb(chatId);
        redisTemplate.opsForValue().set(
            CACHE_KEY_PREFIX + chatId, locale.toLanguageTag(), LOCALE_CACHE_TTL);
        return locale;
    }

    private Locale resolveFromDb(Long chatId) {
        try {
            return localeResolver.resolve(chatId);
        } catch (Exception e) {
            log.debug("Locale resolution failed for chatId={}, using default: {}", chatId, e.getMessage());
            return BotLocaleResolver.DEFAULT_LOCALE;
        }
    }
}
