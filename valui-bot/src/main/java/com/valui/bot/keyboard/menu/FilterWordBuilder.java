package com.valui.bot.keyboard.menu;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Converts human-readable exclusion words ↔ negative-lookahead regex.
 *
 * Stored format: {@code ^(?!.*(?:\Qword1\E|\Qword2\E))}
 *
 * The regex is used with {@code CASE_INSENSITIVE | UNICODE_CASE} flags in
 * {@code SportEventConsumer.passesFilterRule()}.  If the pattern matches a
 * title it means the title is clean (no forbidden words); if it doesn't match
 * (lookahead triggered) the event is blocked.
 */
public final class FilterWordBuilder {

    static final String REGEX_PREFIX = "^(?!.*(?:";
    static final String REGEX_SUFFIX = "))";

    private FilterWordBuilder() {}

    /** Splits raw user input (newline / comma / semicolon separated) into trimmed non-empty words. */
    public static List<String> parseWords(String input) {
        return Arrays.stream(input.split("[\\n,;]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Converts a list of exclusion words into a negative-lookahead regex.
     * Each word is wrapped in {@code \Q...\E} so special regex chars are safe.
     */
    public static String wordsToRegex(List<String> words) {
        String joined = words.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return REGEX_PREFIX + joined + REGEX_SUFFIX;
    }

    /**
     * Extracts human-readable display text from a stored exclusion regex.
     * Falls back to the raw regex string for legacy rules not in this format.
     */
    public static String regexToDisplay(String filterRule) {
        if (filterRule == null) return "";
        if (filterRule.startsWith(REGEX_PREFIX) && filterRule.endsWith(REGEX_SUFFIX)) {
            String inner = filterRule.substring(REGEX_PREFIX.length(),
                    filterRule.length() - REGEX_SUFFIX.length());
            return Arrays.stream(inner.split("\\|"))
                    .map(FilterWordBuilder::unquote)
                    .collect(Collectors.joining(", "));
        }
        return filterRule;
    }

    private static String unquote(String s) {
        if (s.startsWith("\\Q") && s.endsWith("\\E")) {
            return s.substring(2, s.length() - 2);
        }
        return s;
    }
}
