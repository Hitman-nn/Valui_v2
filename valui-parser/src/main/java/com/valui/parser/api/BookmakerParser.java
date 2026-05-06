package com.valui.parser.api;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;

import java.util.List;

public interface BookmakerParser {

    BookmakerType getBookmaker();

    ParseResult<List<SportDto>> fetchSports();

    ParseResult<List<TournamentDto>> fetchTournaments(String sportId);

    ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId);

    default boolean isAvailable() {
        return true;
    }

    /** Cheap in-memory check: connection pool has slots ready (no HTTP).
     *  Override only for parsers that maintain a persistent pool (e.g. WS). */
    default boolean isConnectionReady() {
        return true;
    }
}
