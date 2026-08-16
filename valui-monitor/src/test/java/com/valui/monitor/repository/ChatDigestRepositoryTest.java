package com.valui.monitor.repository;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.NotificationChannel;
import com.valui.common.domain.NotificationStatus;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.NotificationLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the new digest-aggregation queries added to ControllerSubscriptionRepository and
 * NotificationLogRepository — same @DataJpaTest/Testcontainers template as
 * {@link ControllerRepositoryTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(ChatDigestRepositoryTest.TestJpaConfig.class)
@DisplayName("Chat digest repository queries — @DataJpaTest with Testcontainers PostgreSQL")
class ChatDigestRepositoryTest {

    @Configuration
    @EntityScan(basePackages = "com.valui.common.entity")
    @EnableJpaRepositories(basePackages = "com.valui.user.repository")
    static class TestJpaConfig {}

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled",      () -> "false");
    }

    @Autowired TestEntityManager em;
    @Autowired ControllerSubscriptionRepository subscriptionRepository;
    @Autowired NotificationLogRepository notificationLogRepository;

    UserEntity user;
    static final Long GROUP_CHAT = -100L;
    static final Long OTHER_GROUP_CHAT = -200L;
    static final Long DM_CHAT = 555L;

    @BeforeEach
    void setUp() {
        user = em.persist(user());
        em.flush();
    }

    @Test
    @DisplayName("aggregateDigestStatsByChat: counts active/muted/paused separately, excludes DM chats and inactive controllers")
    void aggregateDigestStatsByChat_countsCorrectly() {
        ControllerEntity active1 = em.persist(controller(BookmakerType.XBET));
        ControllerEntity active2 = em.persist(controller(BookmakerType.FONBET));
        ControllerEntity muted   = em.persist(controller(BookmakerType.XBET));
        ControllerEntity paused  = em.persist(controller(BookmakerType.OLIMP));
        ControllerEntity inactive = em.persist(controller(BookmakerType.BETCITY));
        inactive.setIsActive(false);
        em.flush();

        em.persist(subscription(active1, GROUP_CHAT, false, false));
        em.persist(subscription(active2, GROUP_CHAT, false, false));
        em.persist(subscription(muted,   GROUP_CHAT, true,  false));
        em.persist(subscription(paused,  GROUP_CHAT, false, true));
        em.persist(subscription(inactive, GROUP_CHAT, false, false));
        // Same active controller also subscribed from a personal (DM) chat — must not leak into
        // the group-only aggregation.
        em.persist(subscription(active1, DM_CHAT, false, false));
        em.flush();

        Map<Long, Object[]> byChat = toMap(subscriptionRepository.aggregateDigestStatsByChat());

        assertThat(byChat).containsOnlyKeys(GROUP_CHAT);
        Object[] row = byChat.get(GROUP_CHAT);
        assertThat(((Number) row[0]).longValue()).isEqualTo(2); // active1 + active2, not muted/paused/inactive
        assertThat(((Number) row[1]).longValue()).isEqualTo(2); // XBET + FONBET distinct bookmakers
        assertThat(((Number) row[2]).longValue()).isEqualTo(1); // muted
        assertThat(((Number) row[3]).longValue()).isEqualTo(1); // paused
    }

    @Test
    @DisplayName("aggregateDigestStatsByChat: a chat with zero active controllers is absent entirely")
    void aggregateDigestStatsByChat_noEligibleControllers_chatAbsent() {
        ControllerEntity onlyMuted = em.persist(controller(BookmakerType.XBET));
        em.flush();
        em.persist(subscription(onlyMuted, OTHER_GROUP_CHAT, true, false));
        em.flush();

        List<Object[]> rows = subscriptionRepository.aggregateDigestStatsByChat();

        // The chat still appears (isActive=true is the only hard filter — see repository javadoc)
        // but its active-controller column must read 0, not be silently omitted from view.
        Map<Long, Object[]> byChat = toMap(rows);
        assertThat(byChat).containsKey(OTHER_GROUP_CHAT);
        assertThat(((Number) byChat.get(OTHER_GROUP_CHAT)[0]).longValue()).isZero();
        assertThat(((Number) byChat.get(OTHER_GROUP_CHAT)[2]).longValue()).isEqualTo(1); // muted count
    }

    @Test
    @DisplayName("countStaleControllersByChat: 30+ days old with no recent event counts; brand-new or recently-active does not")
    void countStaleControllersByChat_appliesCreatedAtAndLastEventAtCutoff() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusDays(30);

        ControllerEntity staleNeverFired = controllerWithTiming(now.minusDays(40), null);
        ControllerEntity staleOldEvent   = controllerWithTiming(now.minusDays(40), now.minusDays(35));
        ControllerEntity freshRecentEvent = controllerWithTiming(now.minusDays(40), now.minusDays(5));
        ControllerEntity tooNew          = controllerWithTiming(now.minusDays(1), null);
        em.persist(staleNeverFired); em.persist(staleOldEvent);
        em.persist(freshRecentEvent); em.persist(tooNew);
        em.flush();

        em.persist(subscription(staleNeverFired,  GROUP_CHAT, false, false));
        em.persist(subscription(staleOldEvent,    GROUP_CHAT, false, false));
        em.persist(subscription(freshRecentEvent, GROUP_CHAT, false, false));
        em.persist(subscription(tooNew,           GROUP_CHAT, false, false));
        em.flush();

        List<Object[]> rows = subscriptionRepository.countStaleControllersByChat(cutoff);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(GROUP_CHAT);
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(2); // staleNeverFired + staleOldEvent
    }

    @Test
    @DisplayName("countNotificationsByChatBetween: only counts rows within [since, until), ignores null chatId")
    void countNotificationsByChatBetween_windowAndNullChatId() {
        OffsetDateTime now = OffsetDateTime.now();
        em.persist(notification(GROUP_CHAT, now.minusDays(3)));   // in window
        em.persist(notification(GROUP_CHAT, now.minusDays(3)));   // in window
        em.persist(notification(GROUP_CHAT, now.minusDays(10)));  // outside window
        em.persist(notification(null,       now.minusDays(3)));   // no chatId — excluded
        em.flush();

        List<Object[]> rows = notificationLogRepository
                .countNotificationsByChatBetween(now.minusDays(7), now);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(GROUP_CHAT);
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<Long, Object[]> toMap(List<Object[]> rows) {
        Map<Long, Object[]> map = new java.util.HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], new Object[]{row[1], row[2], row[3], row[4]});
        }
        return map;
    }

    private UserEntity user() {
        return UserEntity.builder()
                .telegramId(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .username("digest-test")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private ControllerEntity controller(BookmakerType bookmaker) {
        return controllerWithTiming(bookmaker, OffsetDateTime.now().minusDays(60), OffsetDateTime.now().minusDays(1));
    }

    private ControllerEntity controllerWithTiming(OffsetDateTime createdAt, OffsetDateTime lastEventAt) {
        return controllerWithTiming(BookmakerType.XBET, createdAt, lastEventAt);
    }

    private ControllerEntity controllerWithTiming(BookmakerType bookmaker, OffsetDateTime createdAt, OffsetDateTime lastEventAt) {
        return ControllerEntity.builder()
                .user(user)
                .bookmaker(bookmaker)
                .url("https://example.test/" + UUID.randomUUID())
                .type(ControllerType.TOURNAMENT)
                .isActive(true)
                .isMuted(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .lastEventAt(lastEventAt)
                .build();
    }

    private ControllerSubscriptionEntity subscription(ControllerEntity controller, Long chatId,
                                                        boolean muted, boolean pausedByTokens) {
        return ControllerSubscriptionEntity.builder()
                .controllerId(controller.getId())
                .chatId(chatId)
                .userId(user.getId())
                .telegramId(user.getTelegramId())
                .isMuted(muted)
                .pausedByTokens(pausedByTokens)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private NotificationLogEntity notification(Long chatId, OffsetDateTime createdAt) {
        return NotificationLogEntity.builder()
                .user(user)
                .channel(NotificationChannel.TELEGRAM)
                .status(NotificationStatus.SENT)
                .chatId(chatId)
                .createdAt(createdAt)
                .build();
    }
}
