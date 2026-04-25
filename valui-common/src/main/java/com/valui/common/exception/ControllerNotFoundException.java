package com.valui.common.exception;

import java.util.UUID;

public class ControllerNotFoundException extends ValuiException {

    public ControllerNotFoundException(UUID id) {
        super("Controller not found: id=" + id, 404);
    }
}
