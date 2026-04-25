package com.valui.common.exception;

import java.util.UUID;

public class UserNotFoundException extends ValuiException {

    public UserNotFoundException(UUID id) {
        super("User not found: id=" + id, 404);
    }

    public UserNotFoundException(Long telegramId) {
        super("User not found: telegramId=" + telegramId, 404);
    }
}
