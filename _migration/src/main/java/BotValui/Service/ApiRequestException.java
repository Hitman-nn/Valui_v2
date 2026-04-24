package BotValui.Service;

import java.io.IOException;

public class ApiRequestException extends IOException {
    private final int statusCode;
    private final String bodySnippet;

    public ApiRequestException(String message, int statusCode, String bodySnippet, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.bodySnippet = bodySnippet;
    }

    public ApiRequestException(String message, int statusCode, String bodySnippet) {
        super(message);
        this.statusCode = statusCode;
        this.bodySnippet = bodySnippet;
    }

    public int getStatusCode() { return statusCode; }
    public String getBodySnippet() { return bodySnippet; }
}

