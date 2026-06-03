package com.valui.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limiter for the admin login endpoint.
 * Keyed by remote IP; returns 429 after maxAttempts in the window and fires a BruteForceAlertEvent.
 *
 * State is stored in Redis — survives app restarts and is shared across instances.
 * Uses atomic INCR + EXPIRE: the first increment sets the TTL, subsequent increments
 * within the window accumulate without resetting the expiry (true sliding window per IP).
 */
@Slf4j
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String ADMIN_LOGIN_PATH = "/api/v1/auth/admin/login";
    private static final String KEY_PREFIX       = "auth:rl:";

    private final int                      maxAttempts;
    private final long                     windowSeconds;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate      redisTemplate;

    public AuthRateLimitFilter(
            @Value("${valui.auth.rate-limit.max-attempts:3}") int maxAttempts,
            @Value("${valui.auth.rate-limit.window-seconds:60}") int windowSeconds,
            ApplicationEventPublisher eventPublisher,
            StringRedisTemplate redisTemplate) {
        this.maxAttempts    = maxAttempts;
        this.windowSeconds  = windowSeconds;
        this.eventPublisher = eventPublisher;
        this.redisTemplate  = redisTemplate;
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
        String key = KEY_PREFIX + ip;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) count = 1L;
        if (count == 1L) {
            // First request in this window — set the expiry
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        if (count > maxAttempts) {
            if (count == maxAttempts + 1) {
                // Fire alert exactly once per window — on the first blocked request
                log.error("[AUTH-RL] Admin login brute-force detected ip={} attempts={}", ip, count);
                eventPublisher.publishEvent(new BruteForceAlertEvent(this, ip, count, request.getServletPath()));
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
}
