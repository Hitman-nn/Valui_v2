package BotValui.components;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Slf4j
public class Event implements Comparable<Event> {
    private static final long MAX_TIME_DIFF_MINUTES = 1380;

    private final String eventId;
    private final String link;
    private final String title;
    private final LocalDateTime addDate;

    public Event(String eventId, String link, String title) {
        this.addDate = LocalDateTime.now();
        this.eventId = eventId;
        this.link = link;
        this.title = title;
    }

    public Event(String eventId, String link, String title, String timestamp) {
        this.addDate = LocalDateTime.parse(timestamp);
        this.eventId = eventId;
        this.link = link;
        this.title = title;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event event = (Event) o;

        if (this.eventId.equals(event.eventId)) return true;

        if (this.title.equals(event.title)) {
            Duration duration = Duration.between(this.addDate, event.addDate);
            return Math.abs(duration.toMinutes()) < MAX_TIME_DIFF_MINUTES;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, title);
    }

    @Override
    public String toString() {
        return "Event: " + title + ", href=" + link + ", dataId=" + eventId;
    }

    @Override
    public int compareTo(Event event) {
        return this.eventId.compareTo(event.eventId);
    }
}
