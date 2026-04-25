package com.valui.bot.webhook;

import com.valui.bot.config.BotProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Rejects requests to {@code /webhook/**} that originate outside Telegram's
 * published IP ranges. Only active in webhook mode.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "valui.bot.mode", havingValue = "webhook")
public class TelegramIpFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH_PREFIX = "/webhook/";

    private final List<CidrRange> allowedRanges;

    public TelegramIpFilter(BotProperties botProperties) {
        this.allowedRanges = botProperties.allowedIps().stream()
            .map(CidrRange::parse)
            .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
        throws ServletException, IOException {

        if (!request.getRequestURI().startsWith(WEBHOOK_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = extractClientIp(request);
        if (!isAllowed(ip)) {
            log.warn("Rejected webhook request from IP={}", ip);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "IP not in Telegram range");
            return;
        }

        chain.doFilter(request, response);
    }

    boolean isAllowed(String ip) {
        return allowedRanges.stream().anyMatch(r -> r.contains(ip));
    }

    private static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
