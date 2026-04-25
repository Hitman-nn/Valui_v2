package com.valui.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TelegramIdValidator implements ConstraintValidator<ValidTelegramId, Long> {

    @Override
    public boolean isValid(Long value, ConstraintValidatorContext ctx) {
        return value != null && value > 0;
    }
}
