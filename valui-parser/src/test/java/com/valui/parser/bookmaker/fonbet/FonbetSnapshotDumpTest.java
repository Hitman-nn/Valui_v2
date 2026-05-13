package com.valui.parser.bookmaker.fonbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Manual exploration test — fetches a live Fonbet snapshot and saves it to disk.
 *
 * Run manually via IDE or:
 *   mvn test -pl valui-parser -Dtest=FonbetSnapshotDumpTest -DfailIfNoTests=false
 *
 * Output files in target/fonbet-dump/:
 *   snapshot-full.json          — полный ответ API (~3-5 МБ)
 *   snapshot-rpl-events.json    — только события Россия. Премьер-лига
 *   snapshot-rpl-factors.json   — customFactors для этих событий
 */
@Disabled("Manual exploration — run on demand")
class FonbetSnapshotDumpTest {

    private static final String API_URL =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void dumpRussiaPremierLeagueSnapshot() throws Exception {
        Path outDir = Path.of("target", "fonbet-dump");
        Files.createDirectories(outDir);

        // ── 1. Fetch raw snapshot ─────────────────────────────────────────────
        System.out.println("Fetching Fonbet snapshot from: " + API_URL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.printf("HTTP %d, body size: %,d bytes%n",
                response.statusCode(), response.body().length());

        JsonNode snap = MAPPER.readTree(response.body());

        // Save full snapshot
        Path fullFile = outDir.resolve("snapshot-full.json");
        MAPPER.writeValue(fullFile.toFile(), snap);
        System.out.println("Full snapshot saved → " + fullFile.toAbsolutePath());

        // ── 2. Find Russia Premier League in sports array ─────────────────────
        JsonNode sports = snap.path("sports");
        String rplId = null;
        String rplParentId = null;

        System.out.println("\n=== Sports containing 'Россия' or 'Премьер' ===");
        for (JsonNode sport : sports) {
            String name = sport.path("name").asText("");
            if (name.contains("Россия") || name.contains("Премьер") || name.contains("RPL")) {
                System.out.printf("  id=%-8s parentId=%-8s name=%s%n",
                        sport.path("id").asText("?"),
                        sport.path("parentId").asText("-"),
                        name);
                // Pick the first match with parentId (= tournament, not top-level sport)
                if (rplId == null && !sport.path("parentId").isMissingNode()
                        && !sport.path("parentId").isNull()) {
                    rplId       = sport.path("id").asText();
                    rplParentId = sport.path("parentId").asText();
                }
            }
        }

        if (rplId == null) {
            System.out.println("\nРоссия Премьер-лига не найдена. Вот все турниры по Футболу:");
            printAllFootballTournaments(sports);
            return;
        }

        System.out.printf("%nFound RPL: tournamentId=%s  parentSportId=%s%n", rplId, rplParentId);

        // ── 3. Collect RPL events (top-level only, no sub-events) ─────────────
        JsonNode events = snap.path("events");
        List<JsonNode> rplEvents = new ArrayList<>();
        for (JsonNode ev : events) {
            if (!rplId.equals(ev.path("sportId").asText())) continue;
            JsonNode parentId = ev.path("parentId");
            if (!parentId.isMissingNode() && !parentId.isNull()) continue;
            rplEvents.add(ev);
        }
        System.out.printf("RPL top-level events: %d%n", rplEvents.size());

        // Save RPL events
        ArrayNode rplEventsNode = MAPPER.createArrayNode();
        rplEvents.forEach(rplEventsNode::add);
        Path eventsFile = outDir.resolve("snapshot-rpl-events.json");
        MAPPER.writeValue(eventsFile.toFile(), rplEventsNode);
        System.out.println("RPL events saved → " + eventsFile.toAbsolutePath());

        // ── 4. Print all field names found in events ──────────────────────────
        System.out.println("\n=== All field names in RPL events ===");
        java.util.Set<String> allFields = new java.util.TreeSet<>();
        rplEvents.forEach(ev -> ev.fieldNames().forEachRemaining(allFields::add));
        allFields.forEach(f -> System.out.println("  " + f));

        // ── 5. Collect customFactors for RPL event IDs ────────────────────────
        java.util.Set<String> rplEventIds = new java.util.HashSet<>();
        rplEvents.forEach(ev -> rplEventIds.add(ev.path("id").asText()));

        ArrayNode rplFactors = MAPPER.createArrayNode();
        for (JsonNode cf : snap.path("customFactors")) {
            if (rplEventIds.contains(cf.path("e").asText())) rplFactors.add(cf);
        }
        Path factorsFile = outDir.resolve("snapshot-rpl-factors.json");
        MAPPER.writeValue(factorsFile.toFile(), rplFactors);
        System.out.println("\nRPL customFactors saved → " + factorsFile.toAbsolutePath());

        // ── 6. Print all field names in one customFactors entry ───────────────
        if (rplFactors.size() > 0) {
            System.out.println("\n=== customFactors top-level fields ===");
            rplFactors.get(0).fieldNames().forEachRemaining(f -> System.out.println("  " + f));

            System.out.println("\n=== customFactors[0].factors[0] fields (inner factor) ===");
            JsonNode firstFactor = rplFactors.get(0).path("factors");
            if (firstFactor.isArray() && firstFactor.size() > 0) {
                firstFactor.get(0).fieldNames().forEachRemaining(f -> System.out.println("  " + f));
            }
        }

        // ── 7. Pretty-print first RPL event for quick inspection ──────────────
        if (!rplEvents.isEmpty()) {
            System.out.println("\n=== First RPL event (full) ===");
            System.out.println(MAPPER.writeValueAsString(rplEvents.get(0)));
        }

        // ── 8. Save unknown top-level keys in the snapshot ────────────────────
        System.out.println("\n=== Top-level keys in full snapshot ===");
        snap.fieldNames().forEachRemaining(k -> System.out.println("  " + k));
    }

    private static void printAllFootballTournaments(JsonNode sports) {
        String footballId = null;
        for (JsonNode sport : sports) {
            String name = sport.path("name").asText("");
            if ((name.equalsIgnoreCase("Футбол") || name.equalsIgnoreCase("Football"))
                    && sport.path("parentId").isMissingNode()) {
                footballId = sport.path("id").asText();
                System.out.println("  Football top-level id=" + footballId);
                break;
            }
        }
        if (footballId == null) return;
        for (JsonNode sport : sports) {
            if (footballId.equals(sport.path("parentId").asText())) {
                System.out.printf("  id=%-8s %s%n",
                        sport.path("id").asText(), sport.path("name").asText());
            }
        }
    }
}
