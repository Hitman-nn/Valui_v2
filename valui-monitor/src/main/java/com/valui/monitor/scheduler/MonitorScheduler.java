package com.valui.monitor.scheduler;

import com.valui.common.dto.MatchDto;
import com.valui.parser.api.BookmakerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorScheduler {

    private final List<BookmakerParser> parsers;
    private final MatchEventPublisher publisher;

    @Scheduled(fixedDelayString = "${valui.monitor.poll-interval-ms:30000}")
    public void poll() {
        parsers.stream()
               .filter(BookmakerParser::isAvailable)
               .forEach(parser -> {
                   try {
                       List<MatchDto> matches = parser.fetchLiveMatches();
                       publisher.publishNewMatches(parser.bookmaker(), matches);
                   } catch (Exception e) {
                       log.error("Poll failed for {}", parser.bookmaker(), e);
                   }
               });
    }
}