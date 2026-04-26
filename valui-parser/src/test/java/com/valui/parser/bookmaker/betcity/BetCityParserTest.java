package com.valui.parser.bookmaker.betcity;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BetCityParserTest {

    private MockWebServer server;
    private BetCityParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString().replaceAll("/$", "");
        parser = new BetCityParser(base, new BookmakerHttpClient(WebClient.create()));
    }

    @AfterEach
    void tearDown() throws IOException { server.shutdown(); }

    @Test
    void fetchSports_parsesReplySports() throws Exception {
        enqueue(Map.of("reply", Map.of("sports", List.of(
                Map.of("id_sp", "1", "name_sp", "Футбол"),
                Map.of("id_sp", "2", "name_sp", "Теннис")
        ))));
        ParseResult<List<SportDto>> result = parser.fetchSports();
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
    }

    @Test
    void fetchTournaments_parsesChampMap() throws Exception {
        enqueue(Map.of("reply", Map.of("sports", Map.of("1", Map.of("chmps", Map.of(
                "999", Map.of("name_ch", "АПЛ"),
                "888", Map.of("name_ch", "Ла Лига")
        ))))));
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments("1");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
    }

    @Test
    void fetchMatches_postFormData_parsesEvents() throws Exception {
        enqueue(Map.of("reply", Map.of("sports", Map.of("1", Map.of("chmps", Map.of("999", Map.of(
                "evts", Map.of(
                        "7001", Map.of("name_ht", "Зенит", "name_at", "ЦСКА"),
                        "7002", Map.of("name_ht", "Спартак", "name_at", "Динамо")
                )
        )))))));
        ParseResult<List<MatchDto>> result = parser.fetchMatches("999");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).hasSize(2);
    }

    @Test
    void fetchSports_onError_throwsException() {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> parser.fetchSports())
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
    }

    private void enqueue(Object body) throws Exception {
        server.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(body))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
    }
}
