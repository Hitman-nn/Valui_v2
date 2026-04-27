package com.valui.bot.webhook;

import com.valui.bot.config.BotMode;
import com.valui.bot.config.BotProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramIpFilter — unit tests")
class TelegramIpFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private TelegramIpFilter filter;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties(
            "token", "bot", "https://pay.valui.com",
            BotMode.WEBHOOK, "https://example.com/webhook/token",
            "secret", List.of("149.154.0.0/16", "91.108.0.0/16"), null);
        filter = new TelegramIpFilter(props);
    }

    // ─── CIDR matching via isAllowed() ────────────────────────────────────────

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
        "149.154.0.1",
        "149.154.255.255",
        "91.108.0.1",
        "91.108.255.255"
    })
    @DisplayName("isAllowed: Telegram IPs within configured ranges → true")
    void telegramIps_allowed(String ip) {
        assertThat(filter.isAllowed(ip)).isTrue();
    }

    @ParameterizedTest(name = "blocked: {0}")
    @ValueSource(strings = {
        "8.8.8.8",
        "127.0.0.1",
        "192.168.1.1",
        "10.0.0.1",
        "149.155.0.1"   // just outside 149.154.x.x
    })
    @DisplayName("isAllowed: non-Telegram IPs → false")
    void nonTelegramIps_blocked(String ip) {
        assertThat(filter.isAllowed(ip)).isFalse();
    }

    // ─── filter pass-through for non-webhook paths ────────────────────────────

    @Test
    @DisplayName("non-webhook path: request passes through without IP check")
    void nonWebhookPath_passesThrough() throws Exception {
        given(request.getRequestURI()).willReturn("/api/v1/something");
        // No IP check should occur — getRemoteAddr() is NOT stubbed intentionally

        filter.doFilterInternal(request, response, chain);

        then(chain).should().doFilter(request, response);
        then(response).should(never()).sendError(anyInt(), anyString());
    }

    // ─── filter blocks non-Telegram IPs on webhook path ──────────────────────

    @Test
    @DisplayName("webhook path + non-Telegram IP → 403")
    void webhookPath_nonTelegramIp_returns403() throws Exception {
        given(request.getRequestURI()).willReturn("/webhook/mytoken");
        given(request.getHeader("X-Forwarded-For")).willReturn(null);
        given(request.getRemoteAddr()).willReturn("8.8.8.8");

        filter.doFilterInternal(request, response, chain);

        then(response).should().sendError(403, "IP not in Telegram range");
        then(chain).should(never()).doFilter(request, response);
    }

    @Test
    @DisplayName("webhook path + Telegram IP → passes through")
    void webhookPath_telegramIp_passesThrough() throws Exception {
        given(request.getRequestURI()).willReturn("/webhook/mytoken");
        given(request.getHeader("X-Forwarded-For")).willReturn(null);
        given(request.getRemoteAddr()).willReturn("149.154.100.50");

        filter.doFilterInternal(request, response, chain);

        then(chain).should().doFilter(request, response);
        then(response).should(never()).sendError(anyInt(), anyString());
    }

    // ─── X-Forwarded-For header ───────────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For with Telegram IP → passes through")
    void forwardedFor_telegramIp_passesThrough() throws Exception {
        given(request.getRequestURI()).willReturn("/webhook/mytoken");
        given(request.getHeader("X-Forwarded-For")).willReturn("91.108.10.10, 10.0.0.1");

        filter.doFilterInternal(request, response, chain);

        then(chain).should().doFilter(request, response);
    }

    @Test
    @DisplayName("X-Forwarded-For with non-Telegram IP → 403")
    void forwardedFor_nonTelegramIp_returns403() throws Exception {
        given(request.getRequestURI()).willReturn("/webhook/mytoken");
        given(request.getHeader("X-Forwarded-For")).willReturn("1.2.3.4");

        filter.doFilterInternal(request, response, chain);

        then(response).should().sendError(403, "IP not in Telegram range");
    }
}
