package com.valui.parser.bookmaker.olimp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Manual exploration — fetches live Olimp API frames and saves them to disk.
 *
 * Run:
 *   mvn test -pl valui-parser -Dtest=OlimpSnapshotDumpTest -DfailIfNoTests=false
 *
 * Output files in target/olimp-dump/:
 *   sports-raw.json              — /sports (sport list)
 *   competitions-raw.json        — /sports-with-competitions (all sports + tournaments)
 *   events-raw.json              — /planned-events (all events)
 *   rpl-events.json              — events filtered to Россия Премьер-лига
 */
@Disabled("Manual exploration — run on demand")
class OlimpSnapshotDumpTest {

    private static final String API_BASE = "https://www.olimp.bet/api/v4/0/line";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void dumpRussiaPremierLeagueSnapshot() throws Exception {
        Path outDir = Path.of("target", "olimp-dump");
        Files.createDirectories(outDir);

        // ── 1. Fetch sports ───────────────────────────────────────────────────
        System.out.println("Fetching Olimp /sports …");
        JsonNode sports = fetchJson(API_BASE + "/sports");
        save(outDir.resolve("sports-raw.json"), sports);
        System.out.println("  saved sports-raw.json");

        System.out.println("\n=== Top-level keys in a sports item ===");
        if (sports.isArray() && sports.size() > 0) {
            JsonNode first = sports.get(0);
            first.fieldNames().forEachRemaining(k -> System.out.println("  " + k));
            System.out.println("  payload fields:");
            first.path("payload").fieldNames().forEachRemaining(k -> System.out.println("    " + k));
        }

        // ── 2. Fetch sports-with-competitions (tournaments) ───────────────────
        System.out.println("\nFetching Olimp /sports-with-competitions …");
        JsonNode comps = fetchJson(API_BASE + "/sports-with-competitions");
        save(outDir.resolve("competitions-raw.json"), comps);
        System.out.println("  saved competitions-raw.json");

        // Find sport id
        String footballId = null;
        for (JsonNode item : iter(comps)) {
            JsonNode p = item.path("payload");
            String name = p.path("name").asText("");
            if (name.equalsIgnoreCase("Tennis") || name.equalsIgnoreCase("Теннис")) {
                footballId = p.path("id").asText(null);
                System.out.printf("\n  Football sport id=%s%n", footballId);
                break;
            }
        }

        // Find RPL tournament id
        String rplId = null;
        System.out.println("\n=== Tournaments containing 'Россия' or 'Премьер' ===");
        for (JsonNode item : iter(comps)) {
            JsonNode p = item.path("payload");
            JsonNode competitions = p.path("competitions");
            if (!competitions.isArray()) continue;
            for (JsonNode comp : competitions) {
                String name = comp.path("name").asText("");
                if (name.contains("ATP 1000. Рим. Италия. Грунт")) {
                    System.out.printf("  id=%-10s sportId=%-6s name=%s%n",
                            comp.path("id").asText("?"),
                            comp.path("sportId").asText("?"),
                            name);
                    if (rplId == null) rplId = comp.path("id").asText(null);
                }
            }
        }

        if (rplId == null && footballId != null) {
            System.out.println("\nПремьер-лига не найдена. Все футбольные турниры:");
            printFootballTournaments(comps, footballId);
        }

        // Print competition object field structure
        if (comps.isArray() && comps.size() > 0) {
            JsonNode compPayload = comps.get(0).path("payload");
            JsonNode firstComp = compPayload.path("competitions");
            if (firstComp.isArray() && firstComp.size() > 0) {
                System.out.println("\n=== Competition object fields ===");
                firstComp.get(0).fieldNames().forEachRemaining(k -> System.out.println("  " + k));
            }
        }

        // ── 3. Fetch planned-events ───────────────────────────────────────────
        System.out.println("\nFetching Olimp /planned-events …");
        JsonNode events = fetchJson(API_BASE + "/planned-events");
        save(outDir.resolve("events-raw.json"), events);
        System.out.printf("  saved events-raw.json (%d events total)%n",
                events.isArray() ? events.size() : 0);

        // Print event object field structure
        System.out.println("\n=== Top-level keys in an event item ===");
        if (events.isArray() && events.size() > 0) {
            JsonNode first = events.get(0);
            first.fieldNames().forEachRemaining(k -> System.out.println("  " + k));
            System.out.println("  payload fields:");
            TreeSet<String> payloadFields = new TreeSet<>();
            for (JsonNode ev : events) ev.path("payload").fieldNames().forEachRemaining(payloadFields::add);
            payloadFields.forEach(k -> System.out.println("    " + k));
        }

        // ── 4. Filter RPL events ──────────────────────────────────────────────
        if (rplId != null) {
            List<JsonNode> rplEvents = new ArrayList<>();
            for (JsonNode item : iter(events)) {
                if (rplId.equals(item.path("payload").path("competitionId").asText())) {
                    rplEvents.add(item);
                }
            }
            System.out.printf("\nRPL events (id=%s): %d matches%n", rplId, rplEvents.size());
            ArrayNode arr = MAPPER.createArrayNode();
            rplEvents.forEach(arr::add);
            save(outDir.resolve("rpl-events.json"), arr);
            System.out.println("  saved rpl-events.json");

            if (!rplEvents.isEmpty()) {
                System.out.println("\n=== First RPL event (full) ===");
                System.out.println(MAPPER.writeValueAsString(rplEvents.get(0)));
            }
        }

        System.out.println("\nAll files saved to: " + outDir.toAbsolutePath());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity") // disable gzip — HttpClient doesn't auto-decompress
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        // Check for gzip magic bytes and decompress if needed
        if (body.length > 2 && body[0] == (byte) 0x1F && body[1] == (byte) 0x8B) {
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            }
        }
        System.out.printf("  HTTP %d  %,d bytes%n", resp.statusCode(), body.length);
        return MAPPER.readTree(body);
    }

    private static void save(Path path, JsonNode node) throws Exception {
        MAPPER.writeValue(path.toFile(), node);
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        return n != null && n.isArray() ? n : List.of();
    }

    private static void printFootballTournaments(JsonNode comps, String footballId) {
        for (JsonNode item : iter(comps)) {
            JsonNode p = item.path("payload");
            JsonNode competitions = p.path("competitions");
            if (!competitions.isArray()) continue;
            for (JsonNode comp : competitions) {
                if (footballId.equals(comp.path("sportId").asText())) {
                    System.out.printf("  id=%-10s %s%n",
                            comp.path("id").asText(), comp.path("name").asText());
                }
            }
        }
    }
}
