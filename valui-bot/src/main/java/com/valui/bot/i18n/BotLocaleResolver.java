package com.valui.bot.i18n;

import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Resolves a {@link Locale} for a Telegram user based on their stored {@code languageCode}.
 * Falls back to {@link #DEFAULT_LOCALE} for unsupported or missing language codes.
 * This component performs a DB lookup; callers that need caching should use
 * {@link BotMessageSource} instead.
 */
@Component
@RequiredArgsConstructor
public class BotLocaleResolver {

    static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("ru");

    private static final Set<String> SUPPORTED = Set.of("ru", "en");

    private final UserService userService;

    public Locale resolve(Long chatId) {
        String code = userService.findByTelegramId(chatId)
            .map(u -> u.getLanguageCode())
            .filter(c -> c != null && !c.isBlank())
            .orElse("ru");

        return SUPPORTED.contains(code.toLowerCase())
            ? Locale.forLanguageTag(code.toLowerCase())
            : DEFAULT_LOCALE;
    }
}
