package com.valui.bot.config;

import com.valui.bot.ValuiTelegramBot;
import com.valui.bot.handler.CommandRouter;
import com.valui.bot.webhook.ValuiWebhookBot;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.protocol.BasicHttpContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.LongPollingBot;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(BotProperties.class)
public class TelegramBotConfig {

    private final BotProperties botProperties;

    @PostConstruct
    public void validateBotToken() {
        if ("change-me".equals(botProperties.token())) {
            throw new IllegalStateException(
                "TELEGRAM_BOT_TOKEN не задан! Установи переменную окружения TELEGRAM_BOT_TOKEN.");
        }
    }

    // ─── Long-polling mode ────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "long_polling", matchIfMissing = true)
    public ValuiTelegramBot valuiTelegramBot(CommandRouter commandRouter) {
        return new ValuiTelegramBot(commandRouter, botProperties, buildBotOptions(botProperties));
    }

    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "long_polling", matchIfMissing = true)
    public TelegramBotsApi telegramBotsApi(List<LongPollingBot> bots) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(ValuiBotSession.class);
        for (LongPollingBot bot : bots) {
            api.registerBot(bot);
            log.info("Registered Telegram bot (long-polling): {}", bot.getBotUsername());
        }
        if (bots.isEmpty()) {
            log.warn("TelegramBotsApi started with no registered bots");
        }
        return api;
    }

    // ─── Webhook mode ─────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "valui.bot.mode", havingValue = "webhook")
    public ValuiWebhookBot valuiWebhookBot(CommandRouter commandRouter) {
        return new ValuiWebhookBot(commandRouter, botProperties);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static DefaultBotOptions buildBotOptions(BotProperties props) {
        DefaultBotOptions options = new DefaultBotOptions();
        BotProperties.BotProxyProperties proxy = props.proxy();
        if (proxy == null || !proxy.enabled()) {
            return options;
        }

        DefaultBotOptions.ProxyType type = switch (proxy.type().toUpperCase()) {
            case "SOCKS4" -> DefaultBotOptions.ProxyType.SOCKS4;
            case "SOCKS5" -> DefaultBotOptions.ProxyType.SOCKS5;
            default       -> DefaultBotOptions.ProxyType.HTTP;
        };
        options.setProxyType(type);
        options.setProxyHost(proxy.host());
        options.setProxyPort(proxy.port());
        log.info("Telegram bot proxy: {}://{}:{}", proxy.type(), proxy.host(), proxy.port());

        if (proxy.username() != null && !proxy.username().isBlank()) {
            // Apache HttpClient (DefaultAbsSender for sending requests) needs credentials via HttpContext.
            BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                new AuthScope(proxy.host(), proxy.port()),
                new UsernamePasswordCredentials(proxy.username(), proxy.password())
            );
            BasicHttpContext ctx = new BasicHttpContext();
            ctx.setAttribute(HttpClientContext.CREDS_PROVIDER, credsProvider);
            options.setHttpContext(ctx);

            // JDK 8+ disables Basic auth for HTTPS CONNECT tunnels by default.
            // Clear the disabled-schemes list and register a JVM-level Authenticator so
            // any JDK HTTP stack (e.g. DefaultAbsSender fallback paths) can authenticate.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                        proxy.username(), proxy.password().toCharArray());
                }
            });
            log.info("JVM Authenticator set for proxy credentials");
        }

        return options;
    }
}
