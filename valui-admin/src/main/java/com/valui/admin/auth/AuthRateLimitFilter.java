package com.valui.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter for /api/v1/auth/** endpoints.
 * Keyed by remote IP; returns 429 when attempts exceed the configured limit.
 *
 * State is in-process only — resets on app restart, not shared across instances.
 * Sufficient for a single-node admin panel with no reverse proxy in front.
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final int  maxAttempts;
    private final long windowMs;

    // ip → [windowStartMs, requestCount]
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(
            @Value("${valui.auth.rate-limit.max-attempts:10}") int maxAttempts,
            @Value("${valui.auth.rate-limit.window-seconds:60}") int windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowMs    = windowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/v1/auth/");
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
            log.warn("[AUTH-RL] Rate limit exceeded ip={} attempts={}", ip, state[1]);
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
}
