package com.valui.admin.auth.jwt;

import com.valui.admin.security.ValuiPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            ValuiPrincipal principal = jwtService.extractPrincipal(token);

            // Only set if no authentication already in context (e.g., from another filter)
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                var auth = new UsernamePasswordAuthenticationToken(
                    principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            // Trustworthy admin-identity MDC key, distinct from MdcFilter's "userId" (which is
            // populated straight from a client-supplied X-User-Id header and can be spoofed —
            // fine for generic request correlation, not for an audit trail). This one comes from
            // a verified JWT, so every admin log line for this request can be attributed for
            // real. Cleared in finally so it never leaks onto a pooled thread's next request.
            if (principal.telegramId() != null) {
                MDC.put("adminTelegramId", principal.telegramId().toString());
            }
        } catch (JwtException e) {
            // Leave SecurityContext empty — Spring Security will return 401
            log.debug("JWT rejected for {}: {}", request.getServletPath(), e.getMessage());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("adminTelegramId");
        }
    }

    /** Skip filtering for public paths — avoids unnecessary token parsing overhead. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/api/v1/auth/")
            || path.startsWith("/actuator/")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/swagger-ui");
    }
}
