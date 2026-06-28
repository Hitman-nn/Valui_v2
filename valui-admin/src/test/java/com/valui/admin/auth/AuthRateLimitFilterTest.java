package com.valui.admin.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthRateLimitFilter — unit tests")
class AuthRateLimitFilterTest {

    private static final String LOGIN_PATH = "/api/v1/auth/admin/login";
    private static final String TEST_IP    = "192.168.1.1";

    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StringRedisTemplate       redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private FilterChain               chain;

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        filter = new AuthRateLimitFilter(3, 60, eventPublisher, redisTemplate);
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", LOGIN_PATH);
        req.setServletPath(LOGIN_PATH);
        req.setRemoteAddr(TEST_IP);
        return req;
    }

    @Test
    @DisplayName("shouldNotFilter: non-login path is skipped")
    void shouldNotFilter_nonLoginPath() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/other");
        req.setServletPath("/api/v1/other");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter: login path is not skipped")
    void shouldNotFilter_loginPath() {
        assertThat(filter.shouldNotFilter(loginRequest())).isFalse();
    }

    @Test
    @DisplayName("within limit: request passes through, chain called")
    void withinLimit_passesThrough() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(1L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(loginRequest(), response, chain);

        then(chain).should().doFilter(any(), any());
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("first request: sets TTL on Redis key")
    void firstRequest_setsTtl() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(1L);

        filter.doFilterInternal(loginRequest(), new MockHttpServletResponse(), chain);

        then(redisTemplate).should().expire("auth:rl:" + TEST_IP, 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("subsequent requests within limit: no TTL reset, chain called")
    void subsequentRequests_noTtlReset() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(2L);

        filter.doFilterInternal(loginRequest(), new MockHttpServletResponse(), chain);

        then(redisTemplate).should(never()).expire(any(), anyLong(), any());
        then(chain).should().doFilter(any(), any());
    }

    @Test
    @DisplayName("exceeds limit: returns 429 and fires BruteForceAlertEvent once")
    void exceedsLimit_returns429AndFiresEvent() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(4L); // maxAttempts+1

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(loginRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        then(chain).should(never()).doFilter(any(), any());

        ArgumentCaptor<BruteForceAlertEvent> captor = ArgumentCaptor.forClass(BruteForceAlertEvent.class);
        then(eventPublisher).should().publishEvent(captor.capture());
        assertThat(captor.getValue().getIp()).isEqualTo(TEST_IP);
        assertThat(captor.getValue().getAttempts()).isEqualTo(4L);
    }

    @Test
    @DisplayName("far above limit: event not re-fired (only on transition)")
    void farAboveLimit_eventNotRefired() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(10L); // well above threshold

        filter.doFilterInternal(loginRequest(), new MockHttpServletResponse(), chain);

        then(eventPublisher).should(never()).publishEvent(any());
        then(chain).should(never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("exactly at limit: request is allowed")
    void exactlyAtLimit_allowed() throws Exception {
        given(valueOps.increment("auth:rl:" + TEST_IP)).willReturn(3L); // == maxAttempts

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(loginRequest(), response, chain);

        then(chain).should().doFilter(any(), any());
        assertThat(response.getStatus()).isNotEqualTo(429);
    }
}
