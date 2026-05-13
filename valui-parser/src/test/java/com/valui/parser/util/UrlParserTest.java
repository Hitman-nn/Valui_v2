package com.valui.parser.util;

import com.valui.common.domain.BookmakerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UrlParser — unit tests")
class UrlParserTest {

    // ── parseBookmaker ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "https://1xbet.kz/line/football/12345,      XBET",
        "https://1xstavka.ru/line/football/12345,   XBET",
        "https://fon.bet/sports/1/2/3,              FONBET",
        "https://fonbet.ru/sports/1/2,              FONBET",
        "https://www.olimp.bet/line/1/2/3,          OLIMP",
        "https://betcity.ru/ru/line/football/1/2,   BETCITY",
        "https://betboom.ru/sport/football/1/2/3,   BETBOOM",
    })
    void parseBookmaker_knownUrls(String url, BookmakerType expected) {
        assertThat(UrlParser.parseBookmaker(url.trim())).isEqualTo(expected);
    }

    @Test
    void parseBookmaker_unknown_throws() {
        assertThatThrownBy(() -> UrlParser.parseBookmaker("https://example.com/match"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── extractIds — xBet ────────────────────────────────────────────────────

    @Test
    @DisplayName("xBet: tournament URL → sportId + tournamentId, no matchId")
    void xbet_tournamentUrl() {
        String url = "https://1xstavka.ru/line/football/12345-La-Liga";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.XBET);
        assertThat(ids.sportId()).isEqualTo("football");
        assertThat(ids.tournamentId()).isEqualTo("12345");
        assertThat(ids.matchId()).isNull();
    }

    @Test
    @DisplayName("xBet: match URL → sportId + tournamentId + matchId")
    void xbet_matchUrl() {
        String url = "https://1xstavka.ru/line/football/12345/67890-Real-Madrid-Barcelona";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.XBET);
        assertThat(ids.sportId()).isEqualTo("football");
        assertThat(ids.tournamentId()).isEqualTo("12345");
        assertThat(ids.matchId()).isEqualTo("67890");
    }

    // ── extractIds — Fonbet ───────────────────────────────────────────────────

    @Test
    @DisplayName("Fonbet: /sports/{sportId}/tournament/{champId} → no matchId")
    void fonbet_tournamentUrl() {
        String url = "https://fon.bet/sports/1/tournament/999";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.FONBET);
        assertThat(ids.sportId()).isEqualTo("1");
        assertThat(ids.tournamentId()).isEqualTo("999");
        assertThat(ids.matchId()).isNull();
    }

    @Test
    @DisplayName("Fonbet: /sports/{sportId}/{champId}/{matchId} → all ids")
    void fonbet_matchUrl() {
        String url = "https://fon.bet/sports/1/999/12345";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.FONBET);
        assertThat(ids.sportId()).isEqualTo("1");
        assertThat(ids.tournamentId()).isEqualTo("999");
        assertThat(ids.matchId()).isEqualTo("12345");
    }

    // ── extractIds — Olimp ────────────────────────────────────────────────────

    @Test
    @DisplayName("Olimp: tournament URL → no matchId")
    void olimp_tournamentUrl() {
        String url = "https://www.olimp.bet/line/1/5000";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.OLIMP);
        assertThat(ids.sportId()).isEqualTo("1");
        assertThat(ids.tournamentId()).isEqualTo("5000");
        assertThat(ids.matchId()).isNull();
    }

    @Test
    @DisplayName("Olimp: match URL → sportId + tournamentId + matchId")
    void olimp_matchUrl() {
        String url = "https://www.olimp.bet/line/1/5000/99999";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.OLIMP);
        assertThat(ids.sportId()).isEqualTo("1");
        assertThat(ids.tournamentId()).isEqualTo("5000");
        assertThat(ids.matchId()).isEqualTo("99999");
    }

    // ── extractIds — BetCity ──────────────────────────────────────────────────

    @Test
    @DisplayName("BetCity: tournament URL → no matchId")
    void betcity_tournamentUrl() {
        String url = "https://betcity.ru/ru/line/football/777";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.BETCITY);
        assertThat(ids.tournamentId()).isEqualTo("777");
        assertThat(ids.matchId()).isNull();
    }

    @Test
    @DisplayName("BetCity: match URL → tournamentId + matchId")
    void betcity_matchUrl() {
        String url = "https://betcity.ru/ru/line/football/777/55555";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.BETCITY);
        assertThat(ids.tournamentId()).isEqualTo("777");
        assertThat(ids.matchId()).isEqualTo("55555");
    }

    // ── extractIds — BetBoom ──────────────────────────────────────────────────

    @Test
    @DisplayName("BetBoom: match URL → champId + matchId")
    void betboom_matchUrl() {
        String url = "https://betboom.ru/sport/football/100/200/300?period=all";
        ParsedUrlIds ids = UrlParser.extractIds(url, BookmakerType.BETBOOM);
        assertThat(ids.tournamentId()).isEqualTo("200");
        assertThat(ids.matchId()).isEqualTo("300");
    }
}
