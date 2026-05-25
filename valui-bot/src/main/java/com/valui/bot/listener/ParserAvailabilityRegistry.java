package com.valui.bot.listener;

import com.valui.common.domain.BookmakerType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * In-memory registry of currently unavailable bookmaker parsers.
 * Updated by BookmakerIncidentNotifier (valui-app) when parser events arrive.
 * Read by bot keyboard builders and callback handlers to show warnings.
 */
@Component
public class ParserAvailabilityRegistry {

    private final Set<BookmakerType> unavailable =
            Collections.synchronizedSet(EnumSet.noneOf(BookmakerType.class));

    public void markUnavailable(BookmakerType bm) { unavailable.add(bm); }

    public void markAvailable(BookmakerType bm) { unavailable.remove(bm); }

    public boolean isUnavailable(BookmakerType bm) { return unavailable.contains(bm); }

    public Set<BookmakerType> getUnavailable() { return Collections.unmodifiableSet(unavailable); }
}
