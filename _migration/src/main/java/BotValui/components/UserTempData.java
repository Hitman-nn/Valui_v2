package BotValui.components;

import lombok.Getter;

@Getter
public class UserTempData {
    private final String bk;
    private final String sportId;

    public UserTempData(String bk, String sportId) {
        this.bk = bk;
        this.sportId = sportId;
    }
}
