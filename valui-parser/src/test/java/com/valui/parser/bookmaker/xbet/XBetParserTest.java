package com.valui.parser.bookmaker.xbet;

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

class XBetParserTest {

    private MockWebServer server;
    private XBetParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString().replaceAll("/$", "");
        parser = new XBetParser(base, new BookmakerHttpClient(WebClient.create()));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void fetchSports_parsesValueArray() throws Exception {
        enqueue(Map.of("Value", List.of(
                Map.of("I", "1", "N", "Футбол", "E", "football"),
                Map.of("I", "2", "N", "Теннис", "E", "tennis")
        )));

        ParseResult<List<SportDto>> result = parser.fetchSports();

        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
        assertThat(result.data()).extracting(SportDto::alias).contains("football");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fetchSports_onServerError_throwsException() {
        // Without Spring AOP proxy, @CircuitBreaker fallback doesn't engage —
        // the parser propagates the exception directly (as designed).
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> parser.fetchSports())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
    }

    @Test
    void fetchTournaments_filtersBySportId() throws Exception {
        enqueue(Map.of("Value", List.of(
                Map.of("SI", "1", "LI", "1001", "L", "АПЛ", "LE", "epl", "SE", "football"),
                Map.of("SI", "2", "LI", "2001", "L", "ATP", "LE", "atp", "SE", "tennis")
        )));

        ParseResult<List<TournamentDto>> result = parser.fetchTournaments("1");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo("1001");
        assertThat(result.data().get(0).url()).contains("1001");
    }

    @Test
    void fetchMatches_parsesMatchRows() throws Exception {
        // getChampsJsonCached → champs response
        enqueue(Map.of("Value", List.of(
                Map.of("SI", "1", "LI", "1001", "L", "АПЛ", "SE", "football"))));
        // Get1x2_VZip → matches response
        enqueue(Map.of("Value", List.of(
                Map.of("LI", "1001", "CI", "7777", "O1", "Зенит", "O2", "ЦСКА",
                       "O1E", "zenit", "O2E", "cska", "SE", "football"))));

        ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches("1001");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo("7777");
        assertThat(result.data().get(0).title()).isEqualTo("Зенит - ЦСКА");
    }

    private void enqueue(Object body) throws Exception {
        server.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(body))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
    }
}
