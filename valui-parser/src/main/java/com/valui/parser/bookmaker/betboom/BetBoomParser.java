package com.valui.parser.bookmaker.betboom;

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
public class BetBoomParser implements BookmakerParser {

    @Override
    public BookmakerType bookmaker() {
        return BookmakerType.BETBOOM;
    }

    @Override
    public List<MatchDto> fetchLiveMatches() {
        // TODO: implement WebSocket feed
        log.debug("Fetching BetBoom live matches");
        return List.of();
    }
}