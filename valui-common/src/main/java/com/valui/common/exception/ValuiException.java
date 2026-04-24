package com.valui.common.exception;

public class ValuiException extends RuntimeException {

    public ValuiException(String message) {
        super(message);
    }

    public ValuiException(String message, Throwable cause) {
        super(message, cause);
    }
}