package com.valui.admin.auth.jwt;

import com.valui.admin.security.ValuiPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTtlMs;

    private static final String DEV_SECRET_PREFIX = "dmFsdWktdjIt";

    public JwtService(JwtProperties props) {
        this.signingKey  = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
        this.accessTtlMs = props.accessTokenTtlSeconds() * 1_000L;
        if (props.secret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn("⚠️  JWT_SECRET использует DEV-дефолт! Установи переменную JWT_SECRET в продакшн окружении.");
        }
    }

    /**
     * Generates a signed HS256 access token.
     * Claims: sub=userId, telegramId, role, plan, iat, exp.
     */
    public String generateAccessToken(UUID userId, Long telegramId, String role, String planCode) {
        Date now = new Date();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("telegramId", telegramId)
            .claim("role", role)
            .claim("plan", planCode)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + accessTtlMs))
            .signWith(signingKey)
            .compact();
    }

    /**
     * Parses and validates the token signature + expiry.
     * Throws {@link JwtException} for any validation failure.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException e) {
            log.debug("JWT invalid: {}", e.getMessage());
            return false;
        }
    }

    /** Extracts the principal without throwing — returns null on failure. */
    public ValuiPrincipal extractPrincipal(String token) {
        Claims c = parseClaims(token); // throws JwtException on failure
        return new ValuiPrincipal(
            UUID.fromString(c.getSubject()),
            c.get("telegramId", Long.class),
            c.get("role", String.class),
            c.get("plan", String.class)
        );
    }
}
