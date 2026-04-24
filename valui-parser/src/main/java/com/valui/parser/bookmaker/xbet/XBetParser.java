package com.valui.parser.bookmaker.xbet;

import com.valui.common.domain.BookmakerType;
import com.valui.common.dto.MatchDto;
import com.valui.parser.api.BookmakerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class XBetParser implements BookmakerParser {

    @Override
    public BookmakerType bookmaker() {
        return BookmakerType.XBET;
    }

    @Override
    public List<MatchDto> fetchLiveMatches() {
        // TODO: implement REST/WebSocket fetch
        log.debug("Fetching 1xBet live matches");
        return List.of();
    }
}