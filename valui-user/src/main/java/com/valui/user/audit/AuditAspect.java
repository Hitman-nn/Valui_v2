package com.valui.user.audit;

import com.valui.common.annotation.Audit;
import com.valui.common.entity.UserEntity;
import com.valui.common.event.AuditEvent;
import com.valui.user.repository.UserRepository;
import com.valui.user.security.SecurityPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Intercepts methods annotated with {@link Audit} and publishes an {@link AuditEvent}.
 *
 * Identity resolution order:
 *   1. SecurityContext → ValuiPrincipal (REST/JWT requests)
 *   2. First {@code Long} method argument → telegramId (Bot context)
 *   3. Anonymous (neither principal nor chatId found)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;
    private final UserRepository userRepository;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        Identity identity = resolveIdentity(pjp.getArgs());
        String ipAddress  = resolveIp();
        Map<String, Object> baseDetails = buildDetails(pjp);

        try {
            Object result = pjp.proceed();

            UUID entityId = resolveEntityId(pjp.getArgs(), result);
            publish(audit, identity, entityId, ipAddress, baseDetails, null);
            return result;

        } catch (Throwable t) {
            Map<String, Object> errorDetails = new HashMap<>(baseDetails);
            errorDetails.put("error", t.getClass().getSimpleName());
            errorDetails.put("errorMessage", t.getMessage());

            publish(audit, identity, resolveEntityId(pjp.getArgs(), null), ipAddress, errorDetails, t);
            throw t;
        }
    }

    // ── identity ──────────────────────────────────────────────────────────────

    private Identity resolveIdentity(Object[] args) {
        // 1. REST: SecurityContext holds ValuiPrincipal (or any SecurityPrincipal impl)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecurityPrincipal sp) {
            return new Identity(sp.userId(), sp.telegramId());
        }

        // 2. Bot: first Long arg is telegramId (chatId)
        for (Object arg : args) {
            if (arg instanceof Long chatId) {
                UUID userId = userRepository.findByTelegramId(chatId)
                        .map(UserEntity::getId)
                        .orElse(null);
                return new Identity(userId, chatId);
            }
        }

        return Identity.anonymous();
    }

    // ── entity ────────────────────────────────────────────────────────────────

    private UUID resolveEntityId(Object[] args, Object result) {
        // Prefer UUID from method args (controllerId, userId, subscriptionId…)
        for (Object arg : args) {
            if (arg instanceof UUID id) return id;
        }
        // Fall back to id on the returned DTO/entity (e.g. addController return value)
        if (result != null) {
            try {
                Object id = result.getClass().getMethod("id").invoke(result);
                if (id instanceof UUID uid) return uid;
            } catch (Exception ignored) {
                try {
                    Object id = result.getClass().getMethod("getId").invoke(result);
                    if (id instanceof UUID uid) return uid;
                } catch (Exception ignored2) { /* no id on result */ }
            }
        }
        return null;
    }

    // ── details ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildDetails(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names      = sig.getParameterNames();
        Object[] args       = pjp.getArgs();

        Map<String, Object> details = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            // Skip large/sensitive objects — store only primitives and UUIDs
            Object arg = args[i];
            if (arg == null || arg instanceof String || arg instanceof Number
                    || arg instanceof Boolean || arg instanceof UUID) {
                details.put(names[i], arg);
            } else {
                details.put(names[i], arg.getClass().getSimpleName());
            }
        }
        return details;
    }

    // ── ip ────────────────────────────────────────────────────────────────────

    private String resolveIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            return (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : request.getRemoteAddr();
        } catch (Exception e) {
            return null; // non-web context (Bot, scheduler, etc.)
        }
    }

    // ── publish ───────────────────────────────────────────────────────────────

    private void publish(Audit audit, Identity id, UUID entityId,
                         String ipAddress, Map<String, Object> details, Throwable error) {
        try {
            String entityType = audit.entityType().isBlank() ? null : audit.entityType();
            AuditEvent event = AuditEvent.builder()
                    .userId(id.userId())
                    .telegramId(id.telegramId())
                    .action(audit.action())
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details)
                    .ipAddress(ipAddress)
                    .occurredAt(Instant.now())
                    .build();
            auditService.log(event);
        } catch (Exception e) {
            log.error("[AUDIT] Failed to publish audit for action={}: {}", audit.action(), e.getMessage());
        }
    }

    // ── value object ──────────────────────────────────────────────────────────

    private record Identity(UUID userId, Long telegramId) {
        static Identity anonymous() { return new Identity(null, null); }
    }
}
