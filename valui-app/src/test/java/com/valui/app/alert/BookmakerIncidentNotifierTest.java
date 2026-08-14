package com.valui.app.alert;

import com.valui.bot.listener.ParserAvailabilityRegistry;
import com.valui.common.domain.BookmakerType;
import com.valui.parser.health.ParserRecoveredEvent;
import com.valui.parser.health.ParserUnavailableEvent;
import com.valui.user.repository.ControllerSubscriptionRepository;
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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookmakerIncidentNotifier — CB fast trigger publishes, slow-poll backstop notifies, dedup, user toggle")
class BookmakerIncidentNotifierTest {

    @Mock AbsSender bot;
    @Mock ControllerSubscriptionRepository subscriptionRepo;
    @Mock ApplicationEventPublisher eventPublisher;

    private ParserAvailabilityRegistry availabilityRegistry;
    private CircuitBreakerRegistry cbRegistry;
    private BookmakerIncidentNotifier notifier;

    @BeforeEach
    void setUp() {
        availabilityRegistry = new ParserAvailabilityRegistry();
        cbRegistry = CircuitBreakerRegistry.ofDefaults();
        notifier = new BookmakerIncidentNotifier(bot, subscriptionRepo, availabilityRegistry, cbRegistry, eventPublisher);
        ReflectionTestUtils.setField(notifier, "usersAlertsEnabled", true);
        notifier.subscribeToCbEvents();
    }

    @Nested
    @DisplayName("Fast trigger: circuit breaker transitions only publish (see class javadoc — must not do I/O on the CB thread)")
    class FastTrigger {

        @Test
        @DisplayName("CLOSED_TO_OPEN publishes a ParserUnavailableEvent for the right bookmaker")
        void opens_publishesEvent() {
            cbRegistry.circuitBreaker("xbet-cb").transitionToOpenState();

            ArgumentCaptor<ParserUnavailableEvent> captor = ArgumentCaptor.forClass(ParserUnavailableEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getBookmaker()).isEqualTo(BookmakerType.XBET);
        }

        @Test
        @DisplayName("HALF_OPEN_TO_CLOSED publishes a ParserRecoveredEvent for the right bookmaker")
        void recovers_publishesEvent() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("xbet-cb");
            cb.transitionToOpenState();
            cb.transitionToHalfOpenState();
            cb.transitionToClosedState();

            // Captured as ApplicationEvent (not Object): ParserUnavailableEvent/ParserRecoveredEvent
            // both extend it, so production code's publishEvent(...) resolves to the
            // publishEvent(ApplicationEvent) overload — capturing as Object would instead bind
            // this verify() to the publishEvent(Object) overload and never match.
            ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
            verify(eventPublisher, times(2)).publishEvent(captor.capture());
            assertThat(captor.getAllValues().get(1)).isInstanceOf(ParserRecoveredEvent.class);
            assertThat(((ParserRecoveredEvent) captor.getAllValues().get(1)).getBookmaker())
                    .isEqualTo(BookmakerType.XBET);
        }

        @Test
        @DisplayName("A CB name with no matching BookmakerType is ignored, not a crash")
        void unmappableCbName_ignored() {
            cbRegistry.circuitBreaker("some-other-cb").transitionToOpenState();

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("No blocking calls happen on the CB callback thread itself")
        void noBotOrRepoCallsInline() throws Exception {
            cbRegistry.circuitBreaker("xbet-cb").transitionToOpenState();

            verifyNoInteractions(bot);
            verifyNoInteractions(subscriptionRepo);
        }
    }

    @Nested
    @DisplayName("Async entry point: onParserUnavailable/onParserRecovered do the actual notify work")
    class AsyncEntryPoint {

        @Test
        @DisplayName("ParserUnavailableEvent notifies chats and marks the registry")
        void unavailableEvent_notifiesChats() throws Exception {
            given(subscriptionRepo.findActiveChatIdsByBookmaker(BookmakerType.OLIMP))
                    .willReturn(List.of(333L));

            notifier.onParserUnavailable(new ParserUnavailableEvent(this, BookmakerType.OLIMP, 3));

            verify(bot, times(1)).execute(any(SendMessage.class));
            assertThat(availabilityRegistry.isUnavailable(BookmakerType.OLIMP)).isTrue();
        }

        @Test
        @DisplayName("ParserRecoveredEvent with no prior incident is a no-op")
        void recoveredEvent_withoutPriorIncident_noop() throws Exception {
            notifier.onParserRecovered(new ParserRecoveredEvent(this, BookmakerType.BETCITY));

            verify(bot, never()).execute(any(SendMessage.class));
        }

        @Test
        @DisplayName("Fires the same way regardless of which trigger's event reaches it")
        void bothTriggersConvergeOnSameLogic() throws Exception {
            given(subscriptionRepo.findActiveChatIdsByBookmaker(BookmakerType.XBET))
                    .willReturn(List.of(111L, 222L));

            // Simulates the CB path's published event arriving at this same entry point.
            notifier.onParserUnavailable(new ParserUnavailableEvent(this, BookmakerType.XBET, 6));

            verify(bot, times(2)).execute(any(SendMessage.class));
        }
    }

    @Nested
    @DisplayName("Dedup between the two triggers")
    class Dedup {

        @Test
        @DisplayName("First event claims the incident — a second one for the same bookmaker is a no-op")
        void secondEvent_isNoop() throws Exception {
            given(subscriptionRepo.findActiveChatIdsByBookmaker(BookmakerType.XBET))
                    .willReturn(List.of(111L, 222L));

            notifier.onParserUnavailable(new ParserUnavailableEvent(this, BookmakerType.XBET, 3));
            notifier.onParserUnavailable(new ParserUnavailableEvent(this, BookmakerType.XBET, 12));

            // still only the 2 sends from the first event — repo queried once
            verify(bot, times(2)).execute(any(SendMessage.class));
            verify(subscriptionRepo, times(1)).findActiveChatIdsByBookmaker(BookmakerType.XBET);
        }
    }

    @Nested
    @DisplayName("users-enabled toggle")
    class Toggle {

        @Test
        @DisplayName("Disabled — registry still updates but no chat is queried or messaged")
        void disabled_registryUpdatesNoMessages() {
            ReflectionTestUtils.setField(notifier, "usersAlertsEnabled", false);

            notifier.onParserUnavailable(new ParserUnavailableEvent(this, BookmakerType.XBET, 3));

            assertThat(availabilityRegistry.isUnavailable(BookmakerType.XBET)).isTrue();
            verifyNoInteractions(subscriptionRepo);
        }
    }
}
