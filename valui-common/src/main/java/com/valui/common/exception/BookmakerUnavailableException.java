package com.valui.common.exception;

import com.valui.common.domain.BookmakerType;

public class BookmakerUnavailableException extends ValuiException {

    public BookmakerUnavailableException(BookmakerType bookmaker, Throwable cause) {
        super("Bookmaker unavailable: " + bookmaker, 503, cause);
    }
}