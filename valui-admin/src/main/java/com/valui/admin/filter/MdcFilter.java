package com.valui.admin.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates request-correlation MDC fields as early as possible in the filter chain (runs
 * before Spring Security, hence before any authentication happens).
 *
 * <p><b>{@link #MDC_USER_ID} is NOT an authenticated identity</b> — it's copied verbatim from
 * the client-supplied {@code X-User-Id} header (used by the Bot backend to tag its own calls
 * into the Admin API), which any caller can set to anything. It's fine for generic log
 * correlation but must never be treated as an audit-grade "who did this." For admin actions
 * that need real, unforgeable identity, use the {@code adminTelegramId} MDC key instead —
 * populated by {@link com.valui.admin.auth.jwt.JwtAuthenticationFilter} from a verified JWT
 * once Spring Security's chain actually runs (after this filter, since this one is ordered
 * first) — or read {@code SecurityContextHolder} directly in service code (see
 * {@code AuditAspect}, {@code UserServiceImpl.resolveCurrentAdminId()}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID   = "X-User-Id";
    public static final String HEADER_CHAT_ID   = "X-Chat-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** Client-supplied, not authenticated — see class javadoc. */
    public static final String MDC_USER_ID    = "userId";
    public static final String MDC_CHAT_ID    = "chatId";
    public static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String requestId = headerOrGenerate(request.getHeader(HEADER_REQUEST_ID));
            putIfPresent(MDC_USER_ID, request.getHeader(HEADER_USER_ID));
            putIfPresent(MDC_CHAT_ID, request.getHeader(HEADER_CHAT_ID));
            MDC.put(MDC_REQUEST_ID, requestId);

            response.setHeader(HEADER_REQUEST_ID, requestId);

            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_CHAT_ID);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private String headerOrGenerate(String value) {
        return (value != null && !value.isBlank()) ? value : UUID.randomUUID().toString();
    }
}
