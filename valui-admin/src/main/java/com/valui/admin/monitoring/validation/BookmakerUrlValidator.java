package com.valui.admin.monitoring.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class BookmakerUrlValidator implements ConstraintValidator<ValidBookmakerUrl, String> {

    private static final List<Pattern> SUPPORTED = List.of(
            Pattern.compile(".*1xbet\\..*|.*1xstavka\\..*|.*1xbet\\.kz.*", CASE_INSENSITIVE),
            Pattern.compile(".*fonbet\\..*|.*fon\\.bet.*|.*bk6bba-resources\\.com.*", CASE_INSENSITIVE),
            Pattern.compile(".*olimp\\.bet.*", CASE_INSENSITIVE),
            Pattern.compile(".*betcity\\.ru.*", CASE_INSENSITIVE),
            Pattern.compile(".*betboom\\.ru.*|.*sporthub\\.bet.*", CASE_INSENSITIVE)
    );

    @Override
    public boolean isValid(String url, ConstraintValidatorContext ctx) {
        if (url == null || url.isBlank()) return true; // @NotBlank handles the empty case
        try {
            URI.create(url).toURL();
        } catch (Exception e) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate("Некорректный URL: " + e.getMessage())
               .addConstraintViolation();
            return false;
        }
        return SUPPORTED.stream().anyMatch(p -> p.matcher(url).find());
    }
}
