package com.valui.common.exception;

public class InsufficientTokensException extends ValuiException {

    public InsufficientTokensException(int required, int available) {
        super("Недостаточно токенов: требуется " + required + ", доступно " + available, 402);
    }

    public InsufficientTokensException(String message) {
        super(message, 402);
    }
}
