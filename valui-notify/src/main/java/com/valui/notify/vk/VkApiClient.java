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
        String url = API_BASE + "/messages.send" +
                "?peer_id="     + peerId +
                "&message="     + enc(text) +
                "&random_id="   + ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE) +
                "&access_token=" + enc(props.getCommunityToken()) +
                "&v=" + API_VER;

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (root.has("error")) {
                int code = root.path("error").path("error_code").asInt(-1);
                String msg = root.path("error").path("error_msg").asText();
                log.warn("[VK] messages.send failed peerId={}: {} (code={})", peerId, msg, code);
                return -1;
            }
            return root.path("response").asLong(-1);
        } catch (Exception e) {
            log.warn("[VK] messages.send exception peerId={}: {}", peerId, e.getMessage());
            return -1;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
