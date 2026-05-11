package com.valui.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class TokenThresholdEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int balance;
    private final int threshold;

    public TokenThresholdEvent(Object source, Long telegramId, int balance, int threshold) {
        super(source);
        this.telegramId = telegramId;
        this.balance    = balance;
        this.threshold  = threshold;
    }
}
