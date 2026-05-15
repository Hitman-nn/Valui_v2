package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import org.springframework.context.ApplicationEvent;

public class ParserRecoveredEvent extends ApplicationEvent {

    private final BookmakerType bookmaker;

    public ParserRecoveredEvent(Object source, BookmakerType bookmaker) {
        super(source);
        this.bookmaker = bookmaker;
    }

    public BookmakerType getBookmaker() { return bookmaker; }
}
