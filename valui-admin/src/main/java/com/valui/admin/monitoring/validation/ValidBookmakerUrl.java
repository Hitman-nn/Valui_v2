package com.valui.admin.monitoring.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that the URL belongs to a supported bookmaker domain.
 * Passes {@code null} (let @NotBlank handle that separately).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = BookmakerUrlValidator.class)
public @interface ValidBookmakerUrl {
    String message() default "URL не соответствует ни одному поддерживаемому букмекеру " +
            "(1xBet, Fonbet, Olimp, BetCity, BetBoom)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
