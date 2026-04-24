package BotValui.config;

import BotValui.components.Event;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;

@RequiredArgsConstructor
@Getter
public class ControllerInfo {
    private final String link;
    private final String title;
    private final String rulesFilter;
    private final Set<Event> events;
    private final boolean suppressInitialNotifications;
}
