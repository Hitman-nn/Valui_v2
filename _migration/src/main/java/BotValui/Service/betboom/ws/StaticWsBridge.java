package BotValui.Service.betboom.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StaticWsBridge {

    private static WsRequestService INSTANCE;

    @Autowired
    public StaticWsBridge(WsRequestService ws) {
        StaticWsBridge.INSTANCE = ws;
    }

    public static WsRequestService get() {
        return INSTANCE;
    }
}
