package com.valui.common.exception;

public class InsufficientTokensException extends ValuiException {

    private final int required;
    private final int available;

    public InsufficientTokensException(int required, int available) {
        super("Недостаточно токенов: требуется " + required + ", доступно " + available, 402);
        this.required = required;
        this.available = available;
    }

    public InsufficientTokensException(String message) {
        super(message, 402);
        this.required = 0;
        this.available = 0;
    }

    public int getRequired()  { return required; }
    public int getAvailable() { return available; }

    public String toAlertText() {
        if (required > 0) {
            return "⚠️ Недостаточно токенов\n\n"
                 + "Нужно: " + required + " • Баланс: " + available + "\n\n"
                 + "Пополните баланс в настройках (/info)";
        }
        return "⚠️ Недостаточно токенов. Пополните баланс в настройках (/info)";
    }
}
