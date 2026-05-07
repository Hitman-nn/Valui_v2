package com.valui.user.audit;

import com.valui.common.annotation.Audit;
import com.valui.common.event.AuditEvent;
import com.valui.user.repository.UserRepository;
import com.valui.user.security.SecurityPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditAspect — unit tests")
class AuditAspectTest {

    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserRepository            userRepository;
    @Mock ProceedingJoinPoint       pjp;
    @Mock MethodSignature           methodSignature;

    @InjectMocks AuditAspect aspect;

    private final UUID userId     = UUID.randomUUID();
    private final Long telegramId = 987654321L;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        given(pjp.getSignature()).willReturn(methodSignature);
        given(methodSignature.getParameterNames()).willReturn(new String[0]);
    }

    // ── REST context ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("REST principal: publishes AuditApplicationEvent with userId and action after success")
    void restPrincipal_success_publishesEvent() throws Throwable {
        setRestPrincipal(userId, telegramId);
        given(pjp.getArgs()).willReturn(new Object[]{});
        given(pjp.proceed()).willReturn(null);

        aspect.around(pjp, audit("TEST_ACTION", "Resource"));

        AuditEvent event = captureAuditEvent();
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getTelegramId()).isEqualTo(telegramId);
        assertThat(event.getAction()).isEqualTo("TEST_ACTION");
        assertThat(event.getEntityType()).isEqualTo("Resource");
    }

    @Test
    @DisplayName("REST principal: publishes AuditApplicationEvent with error details on exception")
    void restPrincipal_exception_publishesErrorEvent() throws Throwable {
        setRestPrincipal(userId, telegramId);
        given(pjp.getArgs()).willReturn(new Object[]{});
        given(pjp.proceed()).willThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> aspect.around(pjp, audit("FAIL_ACTION", "")))
                .isInstanceOf(RuntimeException.class);

        AuditEvent event = captureAuditEvent();
        assertThat(event.getAction()).isEqualTo("FAIL_ACTION");
        assertThat(event.getDetails()).containsKey("error");
        assertThat(event.getDetails().get("errorMessage")).isEqualTo("boom");
    }

    // ── Bot context ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bot context: extracts userId from Long arg via UserRepository")
    void botContext_longArg_resolvesUserId() throws Throwable {
        given(pjp.getArgs()).willReturn(new Object[]{ "someRequest", telegramId });
        given(methodSignature.getParameterNames()).willReturn(new String[]{ "req", "telegramId" });
        given(userRepository.findByTelegramId(telegramId))
                .willReturn(Optional.of(stubUser(userId)));
        given(pjp.proceed()).willReturn(null);

        aspect.around(pjp, audit("ADD_CONTROLLER", "Controller"));

        AuditEvent event = captureAuditEvent();
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getTelegramId()).isEqualTo(telegramId);
    }

    @Test
    @DisplayName("Bot context: unknown telegramId → publishes event with null userId")
    void botContext_unknownTelegramId_nullUserId() throws Throwable {
        given(pjp.getArgs()).willReturn(new Object[]{ 111L });
        given(methodSignature.getParameterNames()).willReturn(new String[]{ "chatId" });
        given(userRepository.findByTelegramId(any())).willReturn(Optional.empty());
        given(pjp.proceed()).willReturn(null);

        aspect.around(pjp, audit("SOME_OP", ""));

        assertThat(captureAuditEvent().getUserId()).isNull();
    }

    // ── entityId ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("First UUID arg is captured as entityId")
    void uuidArg_capturedAsEntityId() throws Throwable {
        setRestPrincipal(userId, telegramId);
        UUID controllerId = UUID.randomUUID();
        given(pjp.getArgs()).willReturn(new Object[]{ controllerId, telegramId });
        given(methodSignature.getParameterNames()).willReturn(new String[]{ "controllerId", "telegramId" });
        given(pjp.proceed()).willReturn(null);

        aspect.around(pjp, audit("REMOVE_CONTROLLER", "Controller"));

        assertThat(captureAuditEvent().getEntityId()).isEqualTo(controllerId);
    }

    @Test
    @DisplayName("No args and no SecurityContext: publishes anonymous event")
    void noContext_anonymousEvent() throws Throwable {
        given(pjp.getArgs()).willReturn(new Object[]{});
        given(pjp.proceed()).willReturn(null);

        aspect.around(pjp, audit("SYSTEM_OP", ""));

        AuditEvent event = captureAuditEvent();
        assertThat(event.getUserId()).isNull();
        assertThat(event.getTelegramId()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Captures the AuditApplicationEvent published to eventPublisher and extracts
     * the inner AuditEvent for assertion. After M7, the aspect no longer calls
     * auditService.log() directly — it publishes an ApplicationEvent so that
     * AuditEventListener fires it AFTER_COMMIT via @TransactionalEventListener.
     */
    private AuditEvent captureAuditEvent() {
        ArgumentCaptor<AuditApplicationEvent> captor =
                ArgumentCaptor.forClass(AuditApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue().getAuditEvent();
    }

    private void setRestPrincipal(UUID uid, Long tid) {
        SecurityPrincipal principal = new SecurityPrincipal() {
            public UUID userId()     { return uid; }
            public Long telegramId() { return tid; }
        };
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private com.valui.common.entity.UserEntity stubUser(UUID id) {
        com.valui.common.entity.UserEntity u = new com.valui.common.entity.UserEntity();
        u.setId(id);
        return u;
    }

    private Audit audit(String action, String entityType) {
        return new Audit() {
            public Class<Audit> annotationType() { return Audit.class; }
            public String action()      { return action; }
            public String entityType() { return entityType; }
        };
    }
}
