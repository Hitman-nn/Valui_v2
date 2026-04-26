package com.valui.parser.bookmaker.olimp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.parser.dto.MatchDto;
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
        ParseResult<List<MatchDto>> result = parser.fetchMatches("100");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo("500");
    }

    private void enqueue(Object body) throws Exception {
        server.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(body))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
    }
}
