package com.valui.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = TelegramIdValidator.class)
public @interface ValidTelegramId {
    String message() default "Invalid Telegram ID: must be a positive non-zero number";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
