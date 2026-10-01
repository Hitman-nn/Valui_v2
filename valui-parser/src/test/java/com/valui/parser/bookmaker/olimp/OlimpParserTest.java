package com.valui.parser.bookmaker.olimp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OlimpParserTest {

    private MockWebServer server;
    private OlimpParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString().replaceAll("/$", "");
        parser = new OlimpParser(base, new BookmakerHttpClient(WebClient.create()));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void fetchSports_parsesPayloadArray() throws Exception {
        enqueue(List.of(
                Map.of("payload", Map.of("id", "1", "name", "Футбол")),
                Map.of("payload", Map.of("id", "2", "name", "Теннис"))
        ));
        ParseResult<List<SportDto>> result = parser.fetchSports();
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
    }

    @Test
    void fetchTournaments_extractsCompetitions() throws Exception {
        enqueue(List.of(Map.of("payload", Map.of(
                "id", "1",
                "competitions", List.of(
                        Map.of("id", "100", "name", "АПЛ", "sportId", "1"),
                        Map.of("id", "101", "name", "Ла Лига", "sportId", "1")
                )
        ))));
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments("1");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
    }

    @Test
    void fetchMatches_filtersByCompetitionId() throws Exception {
        enqueue(List.of(
                Map.of("payload", Map.of("id", "500", "name", "A - B", "competitionId", "100", "sportId", "1")),
                Map.of("payload", Map.of("id", "501", "name", "C - D", "competitionId", "999", "sportId", "2"))
        ));
        ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches("100");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo("500");
    }

    @Test
    @DisplayName("REGRESSION: a second call right after a failure fails fast without a new HTTP " +
            "attempt — same convoy fix as FonbetParser (many callers each independently retrying " +
            "the same dead endpoint starved the shared DrrDispatcher budget in a real incident)")
    void fetchSports_secondCallWithinFailureCooldown_failsFastNoNewRequest() {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> parser.fetchSports())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);

        assertThatThrownBy(() -> parser.fetchSports())
                .isInstanceOf(OlimpParser.OlimpSnapshotUnavailableException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cooldown is temporary, not permanent — a call issued after it elapses tries the HTTP call again")
    void fetchSports_callAfterCooldownElapses_triesAgain() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> parser.fetchSports()).isInstanceOf(
                org.springframework.web.reactive.function.client.WebClientResponseException.class);

        Thread.sleep(2_100); // FAILURE_COOLDOWN_MS = 2_000
        enqueue(List.of(Map.of("payload", Map.of("id", "1", "name", "Футбол"))));
        ParseResult<List<SportDto>> result = parser.fetchSports();

        assertThat(result.success()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("REGRESSION 01.10: an expired snapshot is served immediately while a slow refresh " +
            "runs in the background — callers must not block on the ~20MB fetch and burn their 8s budget")
    void fetchMatches_expiredSnapshot_servedStaleWhileBackgroundRefreshRuns() throws Exception {
        parser = new OlimpParser(server.url("").toString().replaceAll("/$", ""),
                new BookmakerHttpClient(WebClient.create()), 50);
        enqueue(List.of(Map.of("payload", Map.of("id", "500", "name", "A - B", "competitionId", "100", "sportId", "1"))));
        assertThat(parser.fetchMatches("100").data()).extracting(ParsedMatchDto::id).containsExactly("500");

        Thread.sleep(100); // past the 50ms TTL
        server.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(List.of(
                        Map.of("payload", Map.of("id", "501", "name", "C - D", "competitionId", "100", "sportId", "1")))))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS));

        long t0 = System.currentTimeMillis();
        ParseResult<List<ParsedMatchDto>> stale = parser.fetchMatches("100");
        assertThat(System.currentTimeMillis() - t0).isLessThan(500);
        assertThat(stale.data()).extracting(ParsedMatchDto::id).containsExactly("500");

        // Single-flight: concurrent stale reads don't start a second refresh.
        parser.fetchMatches("100");
        awaitTrue(() -> "501".equals(parser.fetchMatches("100").data().get(0).id()));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A failed background refresh keeps serving the stale snapshot instead of failing callers")
    void fetchMatches_backgroundRefreshFails_keepsServingStale() throws Exception {
        parser = new OlimpParser(server.url("").toString().replaceAll("/$", ""),
                new BookmakerHttpClient(WebClient.create()), 50);
        enqueue(List.of(Map.of("payload", Map.of("id", "500", "name", "A - B", "competitionId", "100", "sportId", "1"))));
        parser.fetchMatches("100");

        Thread.sleep(100);
        server.enqueue(new MockResponse().setResponseCode(503));
        parser.fetchMatches("100");
        awaitTrue(() -> server.getRequestCount() == 2);
        Thread.sleep(200); // let the background thread finish recording the failure

        ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches("100");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).extracting(ParsedMatchDto::id).containsExactly("500");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met within 5s");
            Thread.sleep(50);
        }
    }

    private void enqueue(Object body) throws Exception {
        server.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(body))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
    }
}
