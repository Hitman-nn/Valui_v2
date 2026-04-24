package com.valui.parser.api;

import com.valui.common.domain.BookmakerType;
import com.valui.common.dto.MatchDto;

import java.util.List;

/**
 * Contract every bookmaker parser must implement.
 * Each implementation is a Spring @Component picked up via component scan.
 */
public interface BookmakerParser {

    BookmakerType bookmaker();

    List<MatchDto> fetchLiveMatches();

    default boolean isAvailable() {
        return true;
    }
}