package com.valui.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = BookmakerUrlValidator.class)
public @interface ValidBookmakerUrl {
    String message() default "Invalid bookmaker URL: must be an absolute http/https URL";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
