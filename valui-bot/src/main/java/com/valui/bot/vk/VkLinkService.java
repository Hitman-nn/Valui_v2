package com.valui.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.user.api.ControllerPortService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   1. User calls /vk in Telegram chat X → generateCode(userId, chatId)
 *      → stores "{userId}:{chatId}" in Redis with TTL 15 min
 *   2. User writes the code in the target VK chat (personal or group)
 *   3. VK Long Poll receives message_new → peer_id = where the code was sent
 *   4. handleMessage(peerId, text) → looks up code → links peerId to all subscriptions
 *      of the user that belong to the originating Telegram chatId
 *
 * Result: Telegram chatId ↔ VK peerId, so group chats map to VK group chats.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VkLinkService {

    private static final String LINK_KEY_PREFIX = "vk:link:";
    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final String API_BASE   = "https://api.vk.com/method";
    private static final String API_VER    = "5.199";

    private final VkBotProperties        props;
    private final StringRedisTemplate    redis;
    private final ControllerPortService  controllerPort;
    private final ObjectMapper           mapper;
    private final RestClient             http = RestClient.create();

    private volatile boolean running = false;

    // Long Poll server state
    private volatile String lpServer;
    private volatile String lpKey;
    private volatile String lpTs;

    public boolean isEnabled() {
        return props.isEnabled() && !props.getCommunityToken().isBlank() && props.getGroupId() > 0;
    }

    /**
     * Generates a one-time link code and stores userId + telegramChatId in Redis.
     *
     * @param telegramChatId the Telegram chat from which /vk was issued;
     *                       determines which subscriptions get the VK peer_id
     * @return code string like "VLK-A3K9F2"
     */
    public String generateCode(UUID userId, long telegramChatId) {
        String code  = "VLK-" + randomCode();
        String value = userId + ":" + telegramChatId;
        redis.opsForValue().set(LINK_KEY_PREFIX + code, value, CODE_TTL);
        log.debug("[VK-LINK] Code generated userId={} chatId={}: {}", userId, telegramChatId, code);
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
        log.info("[VK-LP] Long Poll started for groupId={}", props.getGroupId());
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
                        long peerId = msg.path("peer_id").asLong(0);
                        String text = msg.path("text").asText("").trim();
                        if (peerId > 0 && !text.isBlank()) {
                            handleMessage(peerId, text);
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
        String url = API_BASE + "/groups.getLongPollServer?group_id=" + props.getGroupId()
                + "&access_token=" + props.getCommunityToken() + "&v=" + API_VER;
        String body = http.get().uri(url).retrieve().body(String.class);
        JsonNode root = mapper.readTree(body);
        if (root.has("error")) {
            throw new IllegalStateException("VK API error: "
                    + root.path("error").path("error_msg").asText(body));
        }
        JsonNode resp = root.path("response");
        lpServer = resp.path("server").asText();
        lpKey    = resp.path("key").asText();
        lpTs     = resp.path("ts").asText();
        if (lpServer.isBlank()) {
            throw new IllegalStateException("VK getLongPollServer returned empty server. Response: " + body);
        }
        log.debug("[VK-LP] Got server info ts={}", lpTs);
    }

    // ── message handling ──────────────────────────────────────────────────────

    void handleMessage(long vkPeerId, String text) {
        String code     = text.toUpperCase().replaceAll("\\s+", "");
        String redisKey = LINK_KEY_PREFIX + code;
        String stored   = redis.opsForValue().get(redisKey);
        if (stored == null) return;

        // stored = "userId:telegramChatId"
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) return;

        try {
            UUID userId          = UUID.fromString(parts[0]);
            long telegramChatId  = Long.parseLong(parts[1]);
            controllerPort.linkVk(userId, telegramChatId, vkPeerId);
            redis.delete(redisKey);
            log.info("[VK-LINK] Linked vkPeerId={} → userId={} telegramChatId={}",
                    vkPeerId, userId, telegramChatId);
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
