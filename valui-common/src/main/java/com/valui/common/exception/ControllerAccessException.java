package com.valui.common.exception;

import java.util.UUID;

public class ControllerAccessException extends ValuiException {

    public ControllerAccessException(UUID controllerId) {
        super("Access denied to controller: id=" + controllerId, 403);
    }
}
