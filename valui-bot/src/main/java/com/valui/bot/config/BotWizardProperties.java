package com.valui.bot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "valui.bot.wizard")
@Getter
@Setter
public class BotWizardProperties {
    private int sportPageSize        = 10;
    private int tournamentPageSize   = 15;
}
