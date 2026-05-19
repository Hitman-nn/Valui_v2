package com.valui.notify.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
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
     *
     * @return VK message_id on success, -1 on error.
     */
    public long sendMessage(long peerId, String text) {
        String body = buildForm(Map.of(
                "peer_id",      String.valueOf(peerId),
                "message",      text,
                "random_id",    String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)),
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
                if (code == 9) {
                    log.warn("[VK] messages.send rate-limited peerId={} (code=9) — reduce send frequency", peerId);
                } else if (code == 5) {
                    log.warn("[VK] messages.send auth error peerId={}: {} (code=5) — check VK_COMMUNITY_TOKEN", peerId, msg);
                } else {
                    log.warn("[VK] messages.send failed peerId={}: {} (code={})", peerId, msg, code);
                }
                return -1;
            }
            return root.path("response").asLong(-1);
        } catch (Exception e) {
            log.warn("[VK] messages.send exception peerId={}: {}", peerId, e.getMessage());
            return -1;
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
