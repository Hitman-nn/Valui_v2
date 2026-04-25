package com.valui.bot.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BotMessageSource — unit tests")
class BotMessageSourceTest {

    @Mock private BotLocaleResolver localeResolver;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private BotMessageSource botMessageSource;

    private static final Long CHAT_ID = 42L;
    private static final String CACHE_KEY = "bot:locale:42";

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        // Default: no cached locale
        given(valueOps.get(CACHE_KEY)).willReturn(null);

        MessageSource messageSource = buildMessageSource();
        botMessageSource = new BotMessageSource(messageSource, localeResolver, redisTemplate);
    }

    // ─── language selection ───────────────────────────────────────────────────

    @Test
    @DisplayName("RU locale: getMessage returns Russian text")
    void ruLocale_returnsRussianText() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.forLanguageTag("ru"));

        String result = botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        assertThat(result).contains("Команда не распознана");
    }

    @Test
    @DisplayName("EN locale: getMessage returns English text")
    void enLocale_returnsEnglishText() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.ENGLISH);

        String result = botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        assertThat(result).contains("Command not recognized");
    }

    @Test
    @DisplayName("fallback: when localeResolver throws, returns RU text")
    void localeResolverFails_fallbackToRu() {
        given(localeResolver.resolve(CHAT_ID)).willThrow(new RuntimeException("DB down"));

        String result = botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        assertThat(result).contains("Команда не распознана");
    }

    // ─── argument interpolation ───────────────────────────────────────────────

    @Test
    @DisplayName("getMessage with args: {0} placeholder replaced correctly")
    void withArgs_placeholderReplaced() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.forLanguageTag("ru"));

        String result = botMessageSource.getMessage("bot.welcome", CHAT_ID, "Иван");

        assertThat(result).contains("Иван");
    }

    @Test
    @DisplayName("getMessage with args EN: {0} replaced with English template")
    void withArgsEn_placeholderReplacedInEnglish() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.ENGLISH);

        String result = botMessageSource.getMessage("bot.welcome", CHAT_ID, "John");

        assertThat(result).contains("John").contains("Welcome");
    }

    @Test
    @DisplayName("subscription.expired with plan code arg")
    void subscriptionExpired_argsSubstituted() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.forLanguageTag("ru"));

        String result = botMessageSource.getMessage("subscription.expired", CHAT_ID, "PRO");

        assertThat(result).contains("PRO");
    }

    // ─── Redis caching ────────────────────────────────────────────────────────

    @Test
    @DisplayName("cache miss: locale resolved from DB and stored in Redis")
    void cacheMiss_resolvesAndCaches() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.forLanguageTag("ru"));

        botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        then(valueOps).should().set(eq(CACHE_KEY), eq("ru"), any(Duration.class));
    }

    @Test
    @DisplayName("cache hit: locale resolver NOT called (returned from cache)")
    void cacheHit_doesNotCallResolver() {
        given(valueOps.get(CACHE_KEY)).willReturn("en");

        botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        then(localeResolver).should(org.mockito.Mockito.never()).resolve(any());
    }

    @Test
    @DisplayName("cache hit EN: returns English text without DB lookup")
    void cacheHitEn_returnsEnglishText() {
        given(valueOps.get(CACHE_KEY)).willReturn("en");

        String result = botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        assertThat(result).contains("Command not recognized");
    }

    @Test
    @DisplayName("invalidateLocaleCache: deletes Redis key")
    void invalidate_deletesRedisKey() {
        botMessageSource.invalidateLocaleCache(CHAT_ID);

        then(redisTemplate).should().delete(CACHE_KEY);
    }

    // ─── TTL ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("locale cached with 1-hour TTL")
    void cachedWithOneHourTtl() {
        given(localeResolver.resolve(CHAT_ID)).willReturn(Locale.forLanguageTag("ru"));

        botMessageSource.getMessage("bot.unknown_command", CHAT_ID);

        then(valueOps).should().set(anyString(), anyString(),
            eq(BotMessageSource.LOCALE_CACHE_TTL));
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private static MessageSource buildMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");   // resolves messages_ru.properties, messages_en.properties
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
