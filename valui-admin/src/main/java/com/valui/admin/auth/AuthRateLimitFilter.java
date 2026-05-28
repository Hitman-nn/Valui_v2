package com.valui.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sliding-window rate limiter for the admin login endpoint.
 * Keyed by remote IP; returns 429 after maxAttempts in the window and fires a BruteForceAlertEvent.
 *
 * State is in-process only — resets on app restart, not shared across instances.
 * Sufficient for a single-node admin panel with no reverse proxy in front.
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String ADMIN_LOGIN_PATH = "/api/v1/auth/admin/login";

    private final int                      maxAttempts;
    private final long                     windowMs;
    private final ApplicationEventPublisher eventPublisher;

    // ip → [windowStartMs, requestCount]
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(
            @Value("${valui.auth.rate-limit.max-attempts:3}") int maxAttempts,
            @Value("${valui.auth.rate-limit.window-seconds:60}") int windowSeconds,
            ApplicationEventPublisher eventPublisher) {
        this.maxAttempts    = maxAttempts;
        this.windowMs       = windowSeconds * 1000L;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ADMIN_LOGIN_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip  = request.getRemoteAddr();
        long   now = System.currentTimeMillis();

        long[] state = windows.compute(ip, (k, v) -> {
            if (v == null || now - v[0] >= windowMs) return new long[]{now, 1};
            v[1]++;
            return v;
        });

        if (state[1] > maxAttempts) {
            if (state[1] == maxAttempts + 1) {
                // Fire only once per window — exactly on the transition from allowed to blocked
                log.error("[AUTH-RL] Admin login brute-force detected ip={} attempts={}", ip, state[1]);
                eventPublisher.publishEvent(new BruteForceAlertEvent(this, ip, state[1], request.getServletPath()));
            }
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(429);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Rate limit exceeded. Try again later.\","
                    + "\"path\":\"%s\"}".formatted(request.getServletPath()));
            return;
        }

        chain.doFilter(request, response);
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void cleanupExpiredWindows() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMs);
    }
}
