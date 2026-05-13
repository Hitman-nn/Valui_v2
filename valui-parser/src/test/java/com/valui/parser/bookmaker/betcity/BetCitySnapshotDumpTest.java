package com.valui.parser.bookmaker.betcity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Manual exploration — fetches live BetCity API frames and saves them to disk.
 *
 * Run:
 *   mvn test -pl valui-parser -Dtest=BetCitySnapshotDumpTest -DfailIfNoTests=false
 *
 * Output files in target/betcity-dump/:
 *   sports-raw.json          — /sports
 *   champs-raw.json          — /champs?rev=4&ids_sp={footballId}
 *   events-raw.json          — POST /events?rev=6 for RPL
 */
@Disabled("Manual exploration — run on demand")
class BetCitySnapshotDumpTest {

    private static final String API_BASE = "https://ad.betcity.ru/d/off";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void dumpRussiaPremierLeagueSnapshot() throws Exception {
        Path outDir = Path.of("target", "betcity-dump");
        Files.createDirectories(outDir);

        // ── 1. Fetch sports ───────────────────────────────────────────────────
        System.out.println("Fetching BetCity /sports …");
        JsonNode sportsRoot = fetchGet(API_BASE + "/sports");
        save(outDir.resolve("sports-raw.json"), sportsRoot);
        System.out.println("  saved sports-raw.json");

        // Find football sport id
        String footballId = null;
        JsonNode sportsArr = sportsRoot.path("reply").path("sports");
        System.out.println("\n=== Sports ===");
        System.out.println("  reply.sports item fields:");
        if (sportsArr.isArray() && sportsArr.size() > 0)
            sportsArr.get(0).fieldNames().forEachRemaining(k -> System.out.println("    " + k));

        for (JsonNode sp : iter(sportsArr)) {
            String name = sp.path("name_sp").asText("");
            String id   = sp.path("id_sp").asText("");
            System.out.printf("  id_sp=%-5s %s%n", id, name);
            if (footballId == null && (name.contains("Теннис") || name.contains("Tennis"))) {
                footballId = id;
            }
        }

        if (footballId == null) {
            System.out.println("Football not found — using id_sp=1 as fallback");
            footballId = "1";
        }
        System.out.println("\nFootball id_sp=" + footballId);

        // ── 2. Fetch champs for football ──────────────────────────────────────
        System.out.println("\nFetching BetCity /champs for sport " + footballId + " …");
        JsonNode champsRoot = fetchGet(API_BASE + "/champs?rev=4&ids_sp=" + footballId);
        save(outDir.resolve("champs-raw.json"), champsRoot);
        System.out.println("  saved champs-raw.json");

        // reply.sports.{sportId}.chmps.{champId} → {name_ch, ...}
        JsonNode champsNode = champsRoot.path("reply").path("sports")
                .path(footballId).path("chmps");

        // Print champ object fields
        System.out.println("\n=== Champ (tournament) object fields ===");
        if (champsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = champsNode.fields();
            if (it.hasNext()) {
                Map.Entry<String, JsonNode> first = it.next();
                System.out.println("  key: " + first.getKey());
                first.getValue().fieldNames().forEachRemaining(k -> System.out.println("    " + k));
            }
        }

        // Find RPL tournament
        String rplId = null;
        String rplName = null;
        System.out.println("\n=== Tournaments containing 'Россия' or 'Премьер' ===");
        if (champsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = champsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getValue().path("name_ch").asText("");
                if (name.contains("Теннис. WTA 125. Париж. Франция. Пары. Clay.")) {
                    System.out.printf("  id=%-10s %s%n", e.getKey(), name);
                    if (rplId == null || name.equalsIgnoreCase("Теннис. WTA 125. Париж. Франция. Пары. Clay.")) {
                        rplId = e.getKey(); rplName = name;
                    }
                }
            }
        }

        if (rplId == null) {
            System.out.println("RPL not found. All tournaments:");
            if (champsNode.isObject()) {
                champsNode.fields().forEachRemaining(e ->
                    System.out.printf("  id=%-10s %s%n", e.getKey(), e.getValue().path("name_ch").asText()));
            }
            return;
        }
        System.out.printf("%nFound: id=%s  name=%s%n", rplId, rplName);

        // ── 3. Fetch events for RPL (POST multipart) ──────────────────────────
        System.out.println("\nFetching BetCity /events for champ " + rplId + " …");
        String formBody = "ids=" + rplId;
        HttpRequest eventsReq = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/events?rev=6"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        HttpResponse<String> eventsResp = http.send(eventsReq, HttpResponse.BodyHandlers.ofString());
        System.out.printf("  HTTP %d  %,d bytes%n", eventsResp.statusCode(), eventsResp.body().length());
        JsonNode eventsRoot = MAPPER.readTree(eventsResp.body());
        save(outDir.resolve("events-raw.json"), eventsRoot);
        System.out.println("  saved events-raw.json");

        // ── 4. Print event object fields ──────────────────────────────────────
        JsonNode evtsNode = eventsRoot.path("reply").path("sports")
                .path(footballId).path("chmps").path(rplId).path("evts");

        System.out.println("\n=== Event (evts) object fields ===");
        if (evtsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = evtsNode.fields();
            if (it.hasNext()) {
                Map.Entry<String, JsonNode> first = it.next();
                System.out.println("  event key: " + first.getKey());
                TreeSet<String> allFields = new TreeSet<>();
                evtsNode.fields().forEachRemaining(e ->
                        e.getValue().fieldNames().forEachRemaining(allFields::add));
                allFields.forEach(k -> System.out.println("    " + k));

                System.out.println("\n=== First event (full) ===");
                System.out.println(MAPPER.writeValueAsString(first.getValue()));
            }
        }

        // ── 5. Top-level reply structure ──────────────────────────────────────
        System.out.println("\n=== reply.sports top-level keys ===");
        eventsRoot.path("reply").fieldNames().forEachRemaining(k -> System.out.println("  " + k));

        System.out.println("\nAll files saved to: " + outDir.toAbsolutePath());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode fetchGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.printf("  HTTP %d  %,d bytes%n", resp.statusCode(), resp.body().length());
        return MAPPER.readTree(resp.body());
    }

    private static void save(Path path, JsonNode node) throws Exception {
        MAPPER.writeValue(path.toFile(), node);
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        return n != null && n.isArray() ? n : List.of();
    }
}
