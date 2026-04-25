package com.valui.bot.i18n;

import com.valui.common.entity.UserEntity;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotLocaleResolver — unit tests")
class BotLocaleResolverTest {

    @Mock private UserService userService;

    private BotLocaleResolver resolver;

    private static final Long CHAT_ID = 42L;

    @BeforeEach
    void setUp() {
        resolver = new BotLocaleResolver(userService);
    }

    @ParameterizedTest(name = "languageCode={0} → locale={1}")
    @CsvSource({
        "ru, ru",
        "en, en",
        "RU, ru",
        "EN, en"
    })
    @DisplayName("supported language codes resolved correctly (case-insensitive)")
    void supportedCodes_resolvedCorrectly(String code, String expectedTag) {
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.of(userWithLang(code)));

        Locale result = resolver.resolve(CHAT_ID);

        assertThat(result.toLanguageTag()).isEqualTo(expectedTag);
    }

    @Test
    @DisplayName("unsupported language code (e.g. 'fr') → falls back to RU")
    void unsupportedCode_fallsBackToRu() {
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.of(userWithLang("fr")));

        assertThat(resolver.resolve(CHAT_ID)).isEqualTo(BotLocaleResolver.DEFAULT_LOCALE);
    }

    @Test
    @DisplayName("null language code → falls back to RU")
    void nullLanguageCode_fallsBackToRu() {
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.of(userWithLang(null)));

        assertThat(resolver.resolve(CHAT_ID)).isEqualTo(BotLocaleResolver.DEFAULT_LOCALE);
    }

    @Test
    @DisplayName("blank language code → falls back to RU")
    void blankLanguageCode_fallsBackToRu() {
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.of(userWithLang("  ")));

        assertThat(resolver.resolve(CHAT_ID)).isEqualTo(BotLocaleResolver.DEFAULT_LOCALE);
    }

    @Test
    @DisplayName("user not found → falls back to RU")
    void userNotFound_fallsBackToRu() {
        given(userService.findByTelegramId(CHAT_ID)).willReturn(Optional.empty());

        assertThat(resolver.resolve(CHAT_ID)).isEqualTo(BotLocaleResolver.DEFAULT_LOCALE);
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private static UserEntity userWithLang(String code) {
        UserEntity user = new UserEntity();
        user.setLanguageCode(code);
        return user;
    }
}
