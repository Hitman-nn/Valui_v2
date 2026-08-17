package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * REGRESSION: claimOpen/claimClosed used to be a single shared claim (markOpen/markClosed) that
 * IncidentAlertListener (admin) and BookmakerIncidentNotifier (users) both raced over — whichever
 * called it first for a real CB transition silently starved the other. See the class javadoc.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ParserIncidentStateStore — per-consumer claim independence")
class ParserIncidentStateStoreTest {

    private static final String CANONICAL_KEY = "parser:incidents:open";

    @Mock StringRedisTemplate redis;
    @Mock SetOperations<String, String> setOps;

    ParserIncidentStateStore store;

    @BeforeEach
    void setUp() {
        given(redis.opsForSet()).willReturn(setOps);
        store = new ParserIncidentStateStore(redis);
    }

    @Test
    @DisplayName("Two different consumers can each independently claim the same bookmaker's open incident")
    void twoConsumers_bothClaimOpen_independently() {
        // Canonical set: "admin" is first to touch it and genuinely adds it; "users" arrives
        // after and finds it already a member (added=0) — this is the exact shared-key scenario
        // that used to starve the second caller when the return value itself was used for dedup.
        given(setOps.add(CANONICAL_KEY, "XBET")).willReturn(1L, 0L);
        // Each consumer's own claim key is untouched by the other — both genuinely add.
        given(setOps.add("parser:incidents:claimed:admin", "XBET")).willReturn(1L);
        given(setOps.add("parser:incidents:claimed:users", "XBET")).willReturn(1L);

        boolean adminClaimed = store.claimOpen("admin", BookmakerType.XBET);
        boolean usersClaimed = store.claimOpen("users", BookmakerType.XBET);

        assertThat(adminClaimed).isTrue();
        assertThat(usersClaimed).isTrue();
    }

    @Test
    @DisplayName("A consumer's own repeat claim for the same open incident is a no-op (still per-consumer dedup)")
    void sameConsumer_repeatClaimOpen_isNoop() {
        given(setOps.add("parser:incidents:claimed:admin", "OLIMP")).willReturn(1L, 0L);

        boolean first  = store.claimOpen("admin", BookmakerType.OLIMP);
        boolean second = store.claimOpen("admin", BookmakerType.OLIMP);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("claimClosed is independent per consumer, same as claimOpen")
    void twoConsumers_bothClaimClosed_independently() {
        given(setOps.remove(CANONICAL_KEY, "FONBET")).willReturn(1L, 0L);
        given(setOps.remove("parser:incidents:claimed:admin", "FONBET")).willReturn(1L);
        given(setOps.remove("parser:incidents:claimed:users", "FONBET")).willReturn(1L);

        assertThat(store.claimClosed("admin", BookmakerType.FONBET)).isTrue();
        assertThat(store.claimClosed("users", BookmakerType.FONBET)).isTrue();
    }

    @Test
    @DisplayName("claimOpen keeps the canonical set in sync as a side effect regardless of claim outcome")
    void claimOpen_alwaysTouchesCanonicalSet() {
        given(setOps.add("parser:incidents:claimed:admin", "BETCITY")).willReturn(0L);

        store.claimOpen("admin", BookmakerType.BETCITY);

        org.mockito.Mockito.verify(setOps).add(CANONICAL_KEY, "BETCITY");
    }

    @Test
    @DisplayName("markOpen/markClosed (ParserHealthChecker's own dedup) still use the raw canonical claim")
    void markOpenClosed_unaffectedByPerConsumerClaims() {
        given(setOps.add(CANONICAL_KEY, "OLIMP")).willReturn(1L, 0L);

        assertThat(store.markOpen(BookmakerType.OLIMP)).isTrue();
        assertThat(store.markOpen(BookmakerType.OLIMP)).isFalse();
    }
}
