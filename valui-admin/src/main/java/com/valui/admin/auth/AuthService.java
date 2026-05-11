package com.valui.admin.auth;

import com.valui.admin.auth.dto.AdminLoginRequest;
import com.valui.admin.auth.dto.AuthRequest;
import com.valui.admin.auth.dto.AuthResponse;
import com.valui.common.domain.UserRole;
import com.valui.admin.auth.jwt.JwtProperties;
import com.valui.admin.auth.jwt.JwtService;
import com.valui.admin.auth.redis.RefreshToken;
import com.valui.admin.auth.redis.RefreshTokenRepository;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.common.exception.ValuiException;
import com.valui.user.dto.TelegramUserDto;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final JwtService jwtService;
    private final UserService userService;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Issues JWT tokens for a Telegram user.
     * Creates the user record on first call (auto-registration).
     */
    public AuthResponse authenticate(AuthRequest request) {
        if (!authProperties.botSecret().equals(request.botSecret())) {
            throw new ValuiException("Invalid bot secret", 401);
        }

        UserEntity user = userService.registerOrGetUser(
            new TelegramUserDto(request.telegramId(), null, null, null)
        );

        String accessToken = jwtService.generateAccessToken(
            user.getId(), request.telegramId(), user.getRole().name(), null
        );
        String refreshTokenValue = UUID.randomUUID().toString();

        refreshTokenRepository.save(new RefreshToken(
            refreshTokenValue,
            user.getId().toString(),
            request.telegramId(),
            user.getRole().name()
        ));

        log.info("Issued tokens for telegramId={} userId={}", request.telegramId(), user.getId());
        return new AuthResponse(accessToken, refreshTokenValue, jwtProperties.accessTokenTtlSeconds());
    }

    /**
     * Exchanges a valid refresh token for a new access token.
     * Refresh token itself is NOT rotated (stays valid for its original 30-day TTL).
     */
    public AuthResponse refresh(String refreshToken) {
        RefreshToken rt = refreshTokenRepository.findById(refreshToken)
            .orElseThrow(() -> new ValuiException("Refresh token not found or expired", 401));

        UserEntity user = userService.findByTelegramId(rt.getTelegramId())
            .orElseThrow(() -> new UserNotFoundException(UUID.fromString(rt.getUserId())));

        String newAccessToken = jwtService.generateAccessToken(
            user.getId(), rt.getTelegramId(), user.getRole().name(), null
        );

        return new AuthResponse(newAccessToken, refreshToken, jwtProperties.accessTokenTtlSeconds());
    }

    /**
     * Admin panel login: validates adminPassword from config, checks ADMIN role.
     */
    public AuthResponse authenticateAdmin(AdminLoginRequest request) {
        if (authProperties.adminPassword() == null ||
                !authProperties.adminPassword().equals(request.adminPassword())) {
            throw new ValuiException("Invalid admin password", 401);
        }

        UserEntity user = userService.findByTelegramId(request.telegramId())
            .orElseThrow(() -> new UserNotFoundException(request.telegramId()));

        if (user.getRole() != UserRole.ADMIN) {
            throw new ValuiException("Access denied: not an admin", 403);
        }

        String accessToken = jwtService.generateAccessToken(
            user.getId(), request.telegramId(), user.getRole().name(), null
        );
        String refreshTokenValue = java.util.UUID.randomUUID().toString();

        refreshTokenRepository.save(new RefreshToken(
            refreshTokenValue,
            user.getId().toString(),
            request.telegramId(),
            user.getRole().name()
        ));

        log.info("Admin login: telegramId={} userId={}", request.telegramId(), user.getId());
        return new AuthResponse(accessToken, refreshTokenValue, jwtProperties.accessTokenTtlSeconds());
    }

    /** Invalidates the refresh token in Redis — subsequent refresh calls will fail with 401. */
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteById(refreshToken);
        log.debug("Refresh token invalidated: {}", refreshToken.substring(0, 8) + "...");
    }
}
