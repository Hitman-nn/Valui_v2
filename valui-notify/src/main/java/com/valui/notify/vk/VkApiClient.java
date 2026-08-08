package com.valui.notify.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.notify.exception.RetryableNotificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Thin HTTP wrapper for the VK API (java.net.http — no extra dependencies).
 * All calls are blocking; intended for use from virtual threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VkApiClient {

    private static final String API_BASE = "https://api.vk.com/method";
    private static final String API_VER  = "5.199";

    private final VkProperties props;
    private final ObjectMapper  mapper;
    private final HttpClient    http = HttpClient.newHttpClient();

    /**
     * Sends a plain-text message to a VK user.
     * Throws {@link RetryableNotificationException} on any failure; caller decides retry behaviour.
     *
     * @param randomId deterministic dedup key; 0 = VK skips dedup check
     * @return VK message_id on success (always > 0)
     */
    public long sendMessage(long peerId, String text, long randomId) {
        String body = buildForm(Map.of(
                "peer_id",      String.valueOf(peerId),
                "message",      text,
                "random_id",    String.valueOf(randomId),
                "access_token", props.getCommunityToken(),
                "v",            API_VER));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API_BASE + "/messages.send"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (root.has("error")) {
                int code = root.path("error").path("error_code").asInt(-1);
                String msg = root.path("error").path("error_msg").asText();
                log.warn("[VK] messages.send failed peerId={}: {} (code={})", peerId, msg, code);
                // 5=auth, 7=permissions → permanent failure, don't retry
                boolean retryable = (code != 5 && code != 7);
                throw new RetryableNotificationException(
                        "VK API error " + code + ": " + msg, null, retryable, 0);
            }
            long msgId = root.path("response").asLong(-1);
            if (msgId <= 0) {
                throw new RetryableNotificationException(
                        "VK API returned no message_id for peerId=" + peerId, null, true, 0);
            }
            return msgId;
        } catch (RetryableNotificationException e) {
            throw e;
        } catch (Exception e) {
            // Previously silent at this layer (network error, JSON parse failure) — only
            // surfaced one layer up via the wrapped exception's message, losing the exception
            // type. The JSON-error branch above already logs at WARN; this covers the transport
            // failure case the same way.
            log.warn("[VK] messages.send transport error peerId={}: {}", peerId, e.toString());
            throw new RetryableNotificationException(
                    "VK HTTP error peerId=" + peerId + ": " + e.getMessage(), e, true, 0);
        }
    }

    private static String buildForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
