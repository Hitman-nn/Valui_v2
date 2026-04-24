package BotValui.Service;

import BotValui.Commands.Commands;
import BotValui.components.Controller;
import BotValui.config.ChatBotConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventPersistenceService {
    private final static int PERIOD_EVENTS_UPDATE_MIN = 60;
    private final static int DELAY_EVENTS_UPDATE_MIN = 60;

    private final ChatBotConfig chatBotConfig = ChatBotConfig.getInstance();
    private final ThreadPoolManager threadPoolManager = ThreadPoolManager.getInstance();

    @PostConstruct
    public void init() {
        startScheduledEventsUpdate();
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Performing final all controllers export before shutdown...");
        updateControllers();
    }

    private void startScheduledEventsUpdate() {
        threadPoolManager.getExecutor().scheduleAtFixedRate(
                this::updateControllers,
                DELAY_EVENTS_UPDATE_MIN,
                PERIOD_EVENTS_UPDATE_MIN,
                TimeUnit.MINUTES
        );
    }

    private void updateControllers() {
        try {
            Map<Long, List<Controller>> controllerListByChatId = Commands.getControllerListByChatId();
            chatBotConfig.updateControllers(controllerListByChatId);
            log.info("All controllers successfully saved to JSON");
        } catch (Exception e) {
            log.error("Error saving all controllers to JSON", e);
        }
    }

}
