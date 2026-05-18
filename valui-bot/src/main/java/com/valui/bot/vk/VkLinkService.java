package com.valui.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.user.api.ControllerPortService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages VK account linking for Valui users.
 *
 * Flow:
 *   1. User calls /vk in Telegram → generateCode(userId) → stores code in Redis (TTL 15 min)
 *   2. User writes the code to the VK community
 *   3. VK Long Poll receives the message → handleMessage() → links peer_id to all user's subscriptions
 */
@Slf4j
@Service
public class VkLinkService {

    private static final String LINK_KEY_PREFIX = "vk:link:";
    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final String API_BASE   = "https://api.vk.com/method";
    private static final String API_VER    = "5.199";

    @Value("${valui.vk.enabled:false}")
    private boolean enabled;

    @Value("${valui.vk.community-token:}")
    private String communityToken;

    @Value("${valui.vk.group-id:0}")
    private long groupId;

    private final StringRedisTemplate  redis;
    private final ControllerPortService controllerPort;
    private final ObjectMapper          mapper;
    private final RestClient            http = RestClient.create();

    private volatile boolean running = false;

    // Long Poll server state
    private volatile String lpServer;
    private volatile String lpKey;
    private volatile String lpTs;

    public VkLinkService(StringRedisTemplate redis,
                         ControllerPortService controllerPort,
                         ObjectMapper mapper) {
        this.redis          = redis;
        this.controllerPort = controllerPort;
        this.mapper         = mapper;
    }

    public boolean isEnabled() {
        return enabled && !communityToken.isBlank() && groupId > 0;
    }

    /**
     * Generates a one-time link code for the user and stores it in Redis.
     *
     * @return code string like "VLK-A3K9F2"
     */
    public String generateCode(UUID userId) {
        String code = "VLK-" + randomCode();
        redis.opsForValue().set(LINK_KEY_PREFIX + code, userId.toString(), CODE_TTL);
        log.debug("[VK-LINK] Code generated for userId={}: {}", userId, code);
        return code;
    }

    // ── Long Poll lifecycle ───────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void startLongPoll() {
        if (!isEnabled()) {
            log.info("[VK-LP] VK disabled — Long Poll not started");
            return;
        }
        running = true;
        Thread.ofVirtual().name("vk-longpoll").start(this::runLongPoll);
        log.info("[VK-LP] Long Poll started for groupId={}", groupId);
    }

    @PreDestroy
    public void stopLongPoll() {
        running = false;
    }

    // ── core loop ─────────────────────────────────────────────────────────────

    private void runLongPoll() {
        while (running) {
            try {
                if (lpServer == null) fetchServerInfo();

                String url = lpServer + "?act=a_check&key=" + lpKey + "&ts=" + lpTs + "&wait=25";
                String body = http.get().uri(url).retrieve().body(String.class);
                if (body == null) continue;

                JsonNode root = mapper.readTree(body);

                Integer failed = root.has("failed") ? root.get("failed").asInt() : null;
                if (failed != null) {
                    if (failed == 1) {
                        lpTs = root.path("ts").asText(lpTs);
                    } else {
                        // failed=2 (key expired) or failed=3 (ts too old) → refetch server
                        lpServer = null;
                    }
                    continue;
                }

                lpTs = root.path("ts").asText(lpTs);

                for (JsonNode update : root.path("updates")) {
                    if ("message_new".equals(update.path("type").asText())) {
                        JsonNode msg = update.path("object").path("message");
                        long fromId = msg.path("from_id").asLong(0);
                        String text = msg.path("text").asText("").trim();
                        if (fromId > 0 && !text.isBlank()) {
                            handleMessage(fromId, text);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[VK-LP] Poll error: {} — retrying in 5s", e.getMessage());
                lpServer = null;
                try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        log.info("[VK-LP] Long Poll stopped");
    }

    private void fetchServerInfo() throws Exception {
        String url = API_BASE + "/groups.getLongPollServer?group_id=" + groupId
                + "&access_token=" + communityToken + "&v=" + API_VER;
        String body = http.get().uri(url).retrieve().body(String.class);
        JsonNode resp = mapper.readTree(body).path("response");
        lpServer = resp.path("server").asText();
        lpKey    = resp.path("key").asText();
        lpTs     = resp.path("ts").asText();
        log.debug("[VK-LP] Got server info ts={}", lpTs);
    }

    // ── message handling ──────────────────────────────────────────────────────

    void handleMessage(long vkPeerId, String text) {
        String code = text.toUpperCase().replaceAll("\\s+", "");
        String redisKey = LINK_KEY_PREFIX + code;
        String userIdStr = redis.opsForValue().get(redisKey);
        if (userIdStr == null) return;

        try {
            UUID userId = UUID.fromString(userIdStr);
            controllerPort.linkVk(userId, vkPeerId);
            redis.delete(redisKey);
            log.info("[VK-LINK] Linked vkPeerId={} → userId={}", vkPeerId, userId);
        } catch (Exception e) {
            log.warn("[VK-LINK] Link failed for code={}: {}", code, e.getMessage());
        }
    }

    private static String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
