package BotValui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;

@Configuration
public class TelegramBotOptionsConfig {

    @Bean
    public DefaultBotOptions telegramBotOptions() {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setProxyType(DefaultBotOptions.ProxyType.HTTP);
        options.setProxyHost("127.0.0.1");
        options.setProxyPort(3128);
        return options;
    }
}