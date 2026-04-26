package com.valui.parser.bookmaker.fonbet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.ParseResult;
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

class FonbetParserTest {

    private MockWebServer server;
    private FonbetParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    private String snapshotBody() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "sports", List.of(
                        Map.of("id", "1", "name", "Футбол"),
                        Map.of("id", "10", "name", "АПЛ", "parentId", "1"),
                        Map.of("id", "2", "name", "Теннис")
                ),
                "events", List.of(
                        Map.of("id", "99", "sportId", "10", "team1", "Team A", "team2", "Team B"),
                        Map.of("id", "100", "sportId", "10", "team1", "Team C", "team2", "Team D",
                               "parentId", "99")  // sub-event, should be filtered out
                )
        ));
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String apiUrl = server.url("/events").toString();
        parser = new FonbetParser(apiUrl, WebClient.create());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchSports_returnsOnlyTopLevel() throws Exception {
        server.enqueue(jsonResponse(snapshotBody()));
        ParseResult<List<SportDto>> result = parser.fetchSports();
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
        assertThat(result.data()).extracting(SportDto::id).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void fetchTournaments_filtersByParentId() throws Exception {
        server.enqueue(jsonResponse(snapshotBody()));
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments("1");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo("10");
        assertThat(result.data().get(0).title()).isEqualTo("АПЛ");
    }

    @Test
    void fetchMatches_filtersTopLevelEvents() throws Exception {
        server.enqueue(jsonResponse(snapshotBody()));
        ParseResult<List<MatchDto>> result = parser.fetchMatches("10");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);  // sub-event filtered out
        assertThat(result.data().get(0).id()).isEqualTo("99");
        assertThat(result.data().get(0).title()).isEqualTo("Team A - Team B");
    }

    @Test
    void fetchSports_onError_returnsError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        ParseResult<List<SportDto>> result = parser.fetchSports();
        assertThat(result.success()).isFalse();
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setBody(body)
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    }
}
