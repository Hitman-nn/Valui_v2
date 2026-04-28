package com.valui.parser.factory;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserFactoryTest {

    private ParserFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ParserFactory(List.of(
                stub(BookmakerType.XBET, true),
                stub(BookmakerType.FONBET, false),
                stub(BookmakerType.OLIMP, true)
        ));
    }

    @Test
    void getParser_byEnum_returnsCorrect() {
        assertThat(factory.getParser(BookmakerType.XBET).getBookmaker()).isEqualTo(BookmakerType.XBET);
        assertThat(factory.getParser(BookmakerType.FONBET).getBookmaker()).isEqualTo(BookmakerType.FONBET);
    }

    @Test
    void getParser_byString_caseInsensitive() {
        assertThat(factory.getParser("xbet").getBookmaker()).isEqualTo(BookmakerType.XBET);
        assertThat(factory.getParser("FONBET").getBookmaker()).isEqualTo(BookmakerType.FONBET);
    }

    @Test
    void getParser_unknownBookmaker_throws() {
        assertThatThrownBy(() -> factory.getParser("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAllAvailableParsers_filtersUnavailable() {
        List<BookmakerParser> available = factory.getAllAvailableParsers();
        assertThat(available).hasSize(2);
        assertThat(available).extracting(BookmakerParser::getBookmaker)
                .containsExactlyInAnyOrder(BookmakerType.XBET, BookmakerType.OLIMP);
    }

    // ── stub factory ──────────────────────────────────────────────────────────

    private static BookmakerParser stub(BookmakerType type, boolean available) {
        return new BookmakerParser() {
            @Override public BookmakerType getBookmaker() { return type; }
            @Override public ParseResult<List<SportDto>> fetchSports() { return ParseResult.ok(List.of(), 0L); }
            @Override public ParseResult<List<TournamentDto>> fetchTournaments(String s) { return ParseResult.ok(List.of(), 0L); }
            @Override public ParseResult<List<ParsedMatchDto>> fetchMatches(String s) { return ParseResult.ok(List.of(), 0L); }
            @Override public boolean isAvailable() { return available; }
        };
    }
}
