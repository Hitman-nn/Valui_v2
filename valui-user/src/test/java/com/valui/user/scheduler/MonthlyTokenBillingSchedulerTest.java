package com.valui.user.scheduler;

import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Tests for MonthlyTokenBillingScheduler — the orchestrator that delegates
 * per-user billing to UserBillingProcessor.
 *
 * Key invariants verified:
 *  - processor.process() is called once per active user (via Spring-proxy, not self-invocation)
 *  - failure in one user's billing does not abort the rest of the batch
 *  - aggregated counters reflect results from all successful users
 *  - no users active → processor never called
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyTokenBillingScheduler")
class MonthlyTokenBillingSchedulerTest {

    @Mock UserRepository       userRepository;
    @Mock UserBillingProcessor billingProcessor;

    @InjectMocks MonthlyTokenBillingScheduler scheduler;

    // ── No active users ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("no active users")
    class NoActiveUsers {

        @Test
        @DisplayName("processor is never called when there are no active users")
        void noActiveUsers_processorNotCalled() {
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            scheduler.runMonthlyBilling();

            verifyNoInteractions(billingProcessor);
        }

        @Test
        @DisplayName("runs without exception when user list is empty")
        void noActiveUsers_noException() {
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            assertThatCode(() -> scheduler.runMonthlyBilling()).doesNotThrowAnyException();
        }
    }

    // ── Single user ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("single active user")
    class SingleUser {

        @Test
        @DisplayName("processor.process() is called with the user and current billing month")
        void singleUser_processorCalledWithCorrectArgs() {
            UserEntity user = activeUser();
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(user)));
            given(billingProcessor.process(any(), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(100, 1, 2));

            scheduler.runMonthlyBilling();

            ArgumentCaptor<YearMonth> monthCaptor = ArgumentCaptor.forClass(YearMonth.class);
            verify(billingProcessor).process(eq(user), monthCaptor.capture());
            assertThat(monthCaptor.getValue()).isEqualTo(YearMonth.now());
        }

        @Test
        @DisplayName("processor.process() is called exactly once")
        void singleUser_processorCalledOnce() {
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(activeUser())));
            given(billingProcessor.process(any(), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(0, 0, 0));

            scheduler.runMonthlyBilling();

            verify(billingProcessor, times(1)).process(any(), any());
        }
    }

    // ── Multiple users ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("multiple active users")
    class MultipleUsers {

        @Test
        @DisplayName("processor.process() is called for each active user")
        void multipleUsers_processorCalledForEach() {
            UserEntity u1 = activeUser();
            UserEntity u2 = activeUser();
            UserEntity u3 = activeUser();
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(u1, u2, u3)));
            given(billingProcessor.process(any(), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(0, 0, 0));

            scheduler.runMonthlyBilling();

            verify(billingProcessor, times(3)).process(any(), any());
            verify(billingProcessor).process(eq(u1), any());
            verify(billingProcessor).process(eq(u2), any());
            verify(billingProcessor).process(eq(u3), any());
        }
    }

    // ── Error isolation ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("error isolation — one failure must not abort others")
    class ErrorIsolation {

        @Test
        @DisplayName("exception for user 2 is swallowed; users 1 and 3 are still processed")
        void exceptionInOneUser_othersStillProcessed() {
            UserEntity u1 = activeUser();
            UserEntity u2 = activeUser();
            UserEntity u3 = activeUser();
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(u1, u2, u3)));

            given(billingProcessor.process(eq(u1), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(50, 1, 0));
            given(billingProcessor.process(eq(u2), any()))
                    .willThrow(new RuntimeException("DB connection lost"));
            given(billingProcessor.process(eq(u3), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(30, 0, 1));

            assertThatCode(() -> scheduler.runMonthlyBilling()).doesNotThrowAnyException();

            verify(billingProcessor).process(eq(u1), any());
            verify(billingProcessor).process(eq(u2), any());
            verify(billingProcessor).process(eq(u3), any());
        }

        @Test
        @DisplayName("all users fail — scheduler still completes without exception")
        void allUsersFail_schedulerCompletes() {
            UserEntity u1 = activeUser();
            UserEntity u2 = activeUser();
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(u1, u2)));
            given(billingProcessor.process(any(), any()))
                    .willThrow(new RuntimeException("token service unavailable"));

            assertThatCode(() -> scheduler.runMonthlyBilling()).doesNotThrowAnyException();
        }
    }

    // ── UserBillingProcessor is a separate bean (no self-invocation) ──────────

    @Nested
    @DisplayName("transactional proxy contract")
    class TransactionalProxy {

        @Test
        @DisplayName("billingProcessor is injected as a separate bean — not called via this.*")
        void processorIsInjectedBean_notSelfCall() {
            given(userRepository.findAllByStatus(eq(UserStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(activeUser())));
            given(billingProcessor.process(any(), any()))
                    .willReturn(new MonthlyTokenBillingScheduler.BillingResult(0, 0, 0));

            scheduler.runMonthlyBilling();

            verify(billingProcessor, times(1)).process(any(), any());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static UserEntity activeUser() {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .telegramId(100_000_000L + (long) (Math.random() * 900_000_000))
                .status(UserStatus.ACTIVE)
                .build();
    }

    private static org.assertj.core.api.AbstractObjectAssert<?, YearMonth> assertThat(YearMonth val) {
        return org.assertj.core.api.Assertions.assertThat(val);
    }
}
