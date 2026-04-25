package com.valui.user.service;

import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.SubscriptionPlanRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * Tests @Cacheable/@CacheEvict behaviour with a minimal Spring context
 * (no @SpringBootApplication required — just Spring AOP + CacheManager).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserServiceCacheTest.CacheTestConfig.class)
@DisplayName("UserService — cache behaviour")
class UserServiceCacheTest {

    /**
     * Minimal Spring context: service + in-memory cache + Mockito mocks as beans.
     * Mocks are defined here so Spring injects the same instances into UserServiceImpl.
     */
    @Configuration
    @EnableCaching
    @Import(UserServiceImpl.class)
    static class CacheTestConfig {

        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("users");
        }

        @Bean UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean SubscriptionRepository subscriptionRepository() {
            return Mockito.mock(SubscriptionRepository.class);
        }

        @Bean SubscriptionPlanRepository subscriptionPlanRepository() {
            return Mockito.mock(SubscriptionPlanRepository.class);
        }

        @Bean ApplicationEventPublisher applicationEventPublisher() {
            return Mockito.mock(ApplicationEventPublisher.class);
        }
    }

    @Autowired private UserService userService;
    @Autowired private CacheManager cacheManager;
    @Autowired private UserRepository userRepository; // same mock instance injected into the service

    private static final Long TELEGRAM_ID = 42L;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        Mockito.reset(userRepository); // clear stubs + invocation history between tests

        var usersCache = cacheManager.getCache("users");
        assertThat(usersCache).isNotNull();
        usersCache.clear();

        user = UserEntity.builder()
            .id(UUID.randomUUID())
            .telegramId(TELEGRAM_ID)
            .username("cacheuser")
            .role(UserRole.USER)
            .status(UserStatus.ACTIVE)
            .build();
    }

    @Test
    @DisplayName("cache miss: first call hits repository")
    void findByTelegramId_cacheMiss_callsRepository() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));

        userService.findByTelegramId(TELEGRAM_ID);

        then(userRepository).should(times(1)).findByTelegramId(TELEGRAM_ID);
    }

    @Test
    @DisplayName("cache hit: second call does NOT hit repository")
    void findByTelegramId_cacheHit_doesNotCallRepository() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));

        userService.findByTelegramId(TELEGRAM_ID); // populates cache
        userService.findByTelegramId(TELEGRAM_ID); // served from cache

        then(userRepository).should(times(1)).findByTelegramId(TELEGRAM_ID);
    }

    @Test
    @DisplayName("cache eviction: updateUsername evicts the key → next call re-queries repository")
    void updateUsername_evictsCache() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));
        given(userRepository.save(user)).willReturn(user);

        userService.findByTelegramId(TELEGRAM_ID);          // (1) cache miss → repo
        userService.updateUsername(TELEGRAM_ID, "newname"); // (2) evicts key; updateUsername also calls repo internally
        userService.findByTelegramId(TELEGRAM_ID);          // (3) cache miss again → repo

        // 3 total: populate + updateUsername's internal lookup + post-evict re-fetch
        then(userRepository).should(times(3)).findByTelegramId(TELEGRAM_ID);
    }

    @Test
    @DisplayName("empty Optional is NOT cached (unless = '#result.isEmpty()')")
    void findByTelegramId_notFound_notCached() {
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.empty());

        userService.findByTelegramId(TELEGRAM_ID);
        userService.findByTelegramId(TELEGRAM_ID);

        // Both calls must reach the repository — empty Optional is excluded from cache
        then(userRepository).should(times(2)).findByTelegramId(TELEGRAM_ID);
    }
}
