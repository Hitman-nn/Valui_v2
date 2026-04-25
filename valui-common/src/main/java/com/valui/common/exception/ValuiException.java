package com.valui.common.exception;

import lombok.Getter;

@Getter
public class ValuiException extends RuntimeException {

    private final int httpStatus;

    public ValuiException(String message) {
        super(message);
        this.httpStatus = 500;
    }

    public ValuiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public ValuiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 500;
    }

    public ValuiException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }
}