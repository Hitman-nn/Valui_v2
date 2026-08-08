package com.valui.bot.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

/**
 * Extends DefaultBotSession to inject a properly configured Apache HttpClient into the
 * ReaderThread. TelegramHttpClientBuilder sets HttpSSLConnectionSocketFactory for HTTP
 * proxy but never adds DefaultProxyRoutePlanner — so Apache HttpClient routes directly
 * to api.telegram.org and tries to resolve the hostname locally, which fails when VPN
 * blocks external DNS. Injecting the route planner makes Apache HttpClient connect to the
 * proxy IP first (no DNS needed), then tunnel to Telegram through it.
 */
@Slf4j
public class ValuiBotSession extends DefaultBotSession {

    @Override
    public synchronized void start() {
        super.start();
        patchHttpClientIfNeeded();
    }

    private void patchHttpClientIfNeeded() {
        try {
            Field optionsField = DefaultBotSession.class.getDeclaredField("options");
            optionsField.setAccessible(true);
            DefaultBotOptions options = (DefaultBotOptions) optionsField.get(this);

            if (options == null
                    || options.getProxyType() != DefaultBotOptions.ProxyType.HTTP
                    || options.getProxyHost() == null
                    || options.getProxyHost().isBlank()) {
                // Common/expected when no proxy is configured (e.g. local dev) — but if a proxy
                // *was* expected in prod and silently isn't applied (misconfigured ProxyType),
                // this DEBUG line is the only trace of which path was taken on this restart.
                log.debug("ValuiBotSession: no HTTP proxy configured — using default HttpClient");
                return;
            }

            HttpHost proxyHost = new HttpHost(options.getProxyHost(), options.getProxyPort());

            HttpClientBuilder builder = HttpClientBuilder.create()
                    .setSSLHostnameVerifier(new NoopHostnameVerifier())
                    .setConnectionTimeToLive(70, TimeUnit.SECONDS)
                    .setMaxConnTotal(100)
                    .setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost));

            // Reuse the CredentialsProvider already set in the options' HttpContext so that
            // Apache HttpClient can respond to 407 Proxy Authentication Required automatically.
            if (options.getHttpContext() != null) {
                Object cp = options.getHttpContext().getAttribute(HttpClientContext.CREDS_PROVIDER);
                if (cp instanceof CredentialsProvider) {
                    builder.setDefaultCredentialsProvider((CredentialsProvider) cp);
                }
            }

            CloseableHttpClient newClient = builder.build();

            Field readerThreadField = DefaultBotSession.class.getDeclaredField("readerThread");
            readerThreadField.setAccessible(true);
            Object readerThread = readerThreadField.get(this);

            if (readerThread == null) {
                log.warn("ValuiBotSession: readerThread is null, cannot inject proxy HttpClient");
                return;
            }

            Field httpClientField = readerThread.getClass().getDeclaredField("httpclient");
            httpClientField.setAccessible(true);

            CloseableHttpClient old = (CloseableHttpClient) httpClientField.get(readerThread);
            httpClientField.set(readerThread, newClient);

            if (old != null) {
                try { old.close(); } catch (IOException ignored) {}
            }

            log.info("ValuiBotSession: injected proxy-aware HttpClient (→ {}:{})",
                    options.getProxyHost(), options.getProxyPort());

        } catch (Exception e) {
            log.warn("ValuiBotSession: failed to inject proxy HttpClient — polling will use default client", e);
        }
    }
}
