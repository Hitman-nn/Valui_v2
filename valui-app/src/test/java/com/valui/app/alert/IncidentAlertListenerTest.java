package com.valui.app.alert;

import com.valui.common.domain.BookmakerType;
import com.valui.notify.service.AdminNotificationService;
import com.valui.parser.health.ParserHealthChecker;
import com.valui.parser.health.ParserIncidentStateStore;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentAlertListener — CB alert cooldown + admin toggle")
class IncidentAlertListenerTest {

    @Mock AdminNotificationService adminNotificationService;
    @Mock ApplicationEventPublisher eventPublisher;

    private IncidentAlertListener listener;

    @BeforeEach
    void setUp() {
        listener = new IncidentAlertListener(
                CircuitBreakerRegistry.ofDefaults(), adminNotificationService, eventPublisher);
        ReflectionTestUtils.setField(listener, "adminAlertsEnabled", true);
    }

    // handleTransition only decides whether/what to publish — see sendAsync tests below for the
    // actual Telegram send, which is dispatched off the CB thread via that published event
    // (see class javadoc: doing the send inline here caused a real production incident).

    @Nested
    @DisplayName("CLOSED_TO_OPEN")
    class Open {

        @Test
        @DisplayName("Publishes one alert request mentioning the bookmaker name")
        void opens_publishesAlert() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);

            ArgumentCaptor<IncidentAlertListener.CbAlertRequest> captor =
                    ArgumentCaptor.forClass(IncidentAlertListener.CbAlertRequest.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().text()).contains("XBET").contains("OPEN");
        }

        @Test
        @DisplayName("Repeat within cooldown is suppressed")
        void repeatWithinCooldown_suppressed() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 60_000L);

            verify(eventPublisher, times(1)).publishEvent(any(IncidentAlertListener.CbAlertRequest.class));
        }

        @Test
        @DisplayName("Repeat after cooldown elapses alerts again")
        void repeatAfterCooldown_alertsAgain() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 200_000L);

            verify(eventPublisher, times(2)).publishEvent(any(IncidentAlertListener.CbAlertRequest.class));
        }

        @Test
        @DisplayName("Different breakers have independent cooldowns")
        void differentBreakers_independentCooldown() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);
            listener.handleTransition("fonbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 1_000L);

            verify(eventPublisher, times(2)).publishEvent(any(IncidentAlertListener.CbAlertRequest.class));
        }

        @Test
        @DisplayName("Disabled via toggle — never publishes")
        void disabled_neverPublishes() {
            ReflectionTestUtils.setField(listener, "adminAlertsEnabled", false);
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("HALF_OPEN_TO_CLOSED")
    class Recovered {

        @Test
        @DisplayName("Includes downtime duration when the OPEN alert fired earlier")
        void recovered_includesDuration() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED, 300_000L);

            ArgumentCaptor<IncidentAlertListener.CbAlertRequest> captor =
                    ArgumentCaptor.forClass(IncidentAlertListener.CbAlertRequest.class);
            verify(eventPublisher, times(2)).publishEvent(captor.capture());
            assertThat(captor.getAllValues().get(1).text()).contains("была недоступна");
        }

        @Test
        @DisplayName("Recovery right after OPEN alert is cooldown-suppressed (shared per-breaker clock)")
        void recoveredWithinCooldown_suppressed() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.CLOSED_TO_OPEN, 0L);
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED, 1_000L);

            verify(eventPublisher, times(1)).publishEvent(any(IncidentAlertListener.CbAlertRequest.class));
        }
    }

    @Nested
    @DisplayName("Reconciled parser events — restart-survives recovery path")
    class Reconciled {

        private final ParserHealthChecker healthChecker =
                new ParserHealthChecker(List.of(), mock(ApplicationEventPublisher.class), mock(ParserIncidentStateStore.class));

        @Test
        @DisplayName("ParserUnavailableEvent sourced from ParserHealthChecker publishes an alert")
        void unavailableFromHealthChecker_publishes() {
            listener.onParserUnavailableReconciled(new ParserUnavailableEvent(healthChecker, BookmakerType.FONBET, 3));

            ArgumentCaptor<IncidentAlertListener.CbAlertRequest> captor =
                    ArgumentCaptor.forClass(IncidentAlertListener.CbAlertRequest.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().text()).contains("FONBET").contains("OPEN");
        }

        @Test
        @DisplayName("ParserRecoveredEvent sourced from ParserHealthChecker publishes a recovery alert — this is the case a restart mid-incident otherwise loses entirely")
        void recoveredFromHealthChecker_publishes() {
            listener.onParserRecoveredReconciled(new ParserRecoveredEvent(healthChecker, BookmakerType.FONBET));

            ArgumentCaptor<IncidentAlertListener.CbAlertRequest> captor =
                    ArgumentCaptor.forClass(IncidentAlertListener.CbAlertRequest.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().text()).contains("FONBET").contains("восстановлен");
        }

        @Test
        @DisplayName("Events NOT sourced from ParserHealthChecker are ignored — the direct CB subscription already covers that case")
        void unavailableFromOtherSource_ignored() {
            listener.onParserUnavailableReconciled(new ParserUnavailableEvent(this, BookmakerType.FONBET, 3));
            listener.onParserRecoveredReconciled(new ParserRecoveredEvent(this, BookmakerType.FONBET));

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Disabled via admin toggle — never publishes")
        void disabled_neverPublishes() {
            ReflectionTestUtils.setField(listener, "adminAlertsEnabled", false);

            listener.onParserUnavailableReconciled(new ParserUnavailableEvent(healthChecker, BookmakerType.FONBET, 3));

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("Other transitions")
    class Other {

        @Test
        @DisplayName("OPEN_TO_HALF_OPEN (probe attempt) never publishes")
        void probeTransition_noAlert() {
            listener.handleTransition("xbet-cb", CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN, 0L);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("sendAsync — the actual Telegram dispatch")
    class SendAsync {

        @Test
        @DisplayName("Forwards the request text to AdminNotificationService")
        void forwardsText() {
            listener.sendAsync(new IncidentAlertListener.CbAlertRequest("⚠️ test"));

            verify(adminNotificationService).alertAdmin("⚠️ test");
        }
    }
}
