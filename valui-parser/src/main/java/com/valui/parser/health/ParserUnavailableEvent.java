package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import org.springframework.context.ApplicationEvent;

public class ParserUnavailableEvent extends ApplicationEvent {

    private final BookmakerType bookmaker;
    private final int consecutiveFailures;

    public ParserUnavailableEvent(Object source, BookmakerType bookmaker, int consecutiveFailures) {
        super(source);
        this.bookmaker = bookmaker;
        this.consecutiveFailures = consecutiveFailures;
    }

    public BookmakerType getBookmaker() { return bookmaker; }
    public int getConsecutiveFailures() { return consecutiveFailures; }

    @Override
    public String toString() {
        return "ParserUnavailableEvent{bookmaker=" + bookmaker +
               ", consecutiveFailures=" + consecutiveFailures + "}";
    }
}
