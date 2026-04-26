package com.valui.parser.api;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;

import java.util.List;

public interface BookmakerParser {

    BookmakerType getBookmaker();

    ParseResult<List<SportDto>> fetchSports();

    ParseResult<List<TournamentDto>> fetchTournaments(String sportId);

    ParseResult<List<MatchDto>> fetchMatches(String tournamentId);

    default boolean isAvailable() {
        return true;
    }
}
