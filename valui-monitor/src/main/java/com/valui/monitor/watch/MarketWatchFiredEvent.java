package com.valui.monitor.watch;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@link com.valui.monitor.scheduler.ControllerTaskExecutor} when a watched
 * handicap or total market appears for the first time in the bookmaker's data.
 *
 * Consumed by {@code MarketWatchAlertListener} (valui-app) which sends a new Telegram message.
 */
public class MarketWatchFiredEvent extends ApplicationEvent {

    private final UUID   watchId;
    private final long   chatId;
    private final long   telegramId;
    private final String marketType;       // "HCAP" | "TOTAL"
    private final String matchTitle;
    private final String matchUrl;
    private final String bookmaker;
    private final String extraData;        // current extraData JSON for formatting the market line
    private final String tournamentTitle;  // controller.title, may be null

    public MarketWatchFiredEvent(Object source, UUID watchId, long chatId, long telegramId,
                                 String marketType, String matchTitle, String matchUrl,
                                 String bookmaker, String extraData, String tournamentTitle) {
        super(source);
        this.watchId         = watchId;
        this.chatId          = chatId;
        this.telegramId      = telegramId;
        this.marketType      = marketType;
        this.matchTitle      = matchTitle;
        this.matchUrl        = matchUrl;
        this.bookmaker       = bookmaker;
        this.extraData       = extraData;
        this.tournamentTitle = tournamentTitle;
    }

    public UUID   watchId()         { return watchId; }
    public long   chatId()          { return chatId; }
    public long   telegramId()      { return telegramId; }
    public String marketType()      { return marketType; }
    public String matchTitle()      { return matchTitle; }
    public String matchUrl()        { return matchUrl; }
    public String bookmaker()       { return bookmaker; }
    public String extraData()       { return extraData; }
    public String tournamentTitle() { return tournamentTitle; }
}
