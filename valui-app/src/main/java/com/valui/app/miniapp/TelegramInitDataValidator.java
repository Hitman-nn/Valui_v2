package com.valui.app.miniapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates Telegram Mini App initData using HMAC-SHA256.
 * Algorithm: https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 */
@Slf4j
@Component
public class TelegramInitDataValidator {

    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secretKey;

    public TelegramInitDataValidator(@Value("${valui.bot.token:change-me}") String botToken) {
        this.secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates the raw initData string and extracts the Telegram user ID.
     *
     * @throws ResponseStatusException 401 if signature is invalid
     */
    public long validate(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing initData");
        }

        Map<String, String> params = parseParams(initData);
        String receivedHash = params.remove("hash");
        if (receivedHash == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing hash in initData");
        }

        String dataCheckString = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));

        String expectedHash = bytesToHex(hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8)));
        if (!expectedHash.equalsIgnoreCase(receivedHash)) {
            log.warn("[MINIAPP] initData signature mismatch");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid initData signature");
        }

        String userJson = params.get("user");
        if (userJson == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No user in initData");
        }
        return extractUserId(userJson);
    }

    private Map<String, String> parseParams(String initData) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String key   = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private long extractUserId(String userJson) {
        // Minimal JSON parsing — avoids Jackson dependency on hot path
        int idIdx = userJson.indexOf("\"id\"");
        if (idIdx < 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No id in user object");
        }
        int colon = userJson.indexOf(':', idIdx);
        int comma = userJson.indexOf(',', colon);
        int brace = userJson.indexOf('}', colon);
        int end   = comma > 0 && comma < brace ? comma : brace;
        String idStr = userJson.substring(colon + 1, end).trim().replace("\"", "");
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user id in initData");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 init failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
