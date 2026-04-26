package com.valui.parser.factory;

import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ParserFactory {

    private final Map<BookmakerType, BookmakerParser> parsers;

    public ParserFactory(List<BookmakerParser> parsers) {
        this.parsers = parsers.stream()
                .collect(Collectors.toMap(BookmakerParser::getBookmaker, Function.identity()));
        log.info("ParserFactory loaded: {}", this.parsers.keySet());
    }

    public BookmakerParser getParser(BookmakerType bookmaker) {
        BookmakerParser parser = parsers.get(bookmaker);
        if (parser == null) throw new IllegalArgumentException("No parser for: " + bookmaker);
        return parser;
    }

    public BookmakerParser getParser(String bookmaker) {
        return getParser(BookmakerType.valueOf(bookmaker.toUpperCase()));
    }

    public List<BookmakerParser> getAllAvailableParsers() {
        return parsers.values().stream()
                .filter(BookmakerParser::isAvailable)
                .toList();
    }
}
