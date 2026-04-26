package com.valui.monitor.repository;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.ControllerRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(ControllerRepositoryTest.TestJpaConfig.class)
@DisplayName("ControllerRepository — @DataJpaTest with Testcontainers PostgreSQL")
class ControllerRepositoryTest {

    @Configuration
    @EntityScan(basePackages = "com.valui.common.entity")
    @EnableJpaRepositories(basePackages = "com.valui.user.repository")
    static class TestJpaConfig {}

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled",      () -> "false");
    }

    @Autowired TestEntityManager em;
    @Autowired ControllerRepository controllerRepository;

    UserEntity user1;
    UserEntity user2;

    @BeforeEach
    void setUp() {
        user1 = em.persist(user("u1@test"));
        user2 = em.persist(user("u2@test"));
        em.flush();
    }

    @Test
    @DisplayName("findByIdAndUserId: returns controller when id and userId match")
    void findByIdAndUserId_hit() {
        ControllerEntity c = em.persistAndFlush(controller(user1, "https://1xstavka.ru/line/football/1"));

        Optional<ControllerEntity> result = controllerRepository.findByIdAndUserId(c.getId(), user1.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(c.getId());
    }

    @Test
    @DisplayName("findByIdAndUserId: returns empty when userId does not match")
    void findByIdAndUserId_wrongUser_empty() {
        ControllerEntity c = em.persistAndFlush(controller(user1, "https://1xstavka.ru/line/football/2"));

        Optional<ControllerEntity> result = controllerRepository.findByIdAndUserId(c.getId(), user2.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdOrderByCreatedAtDesc: returns page sorted by createdAt descending")
    void findByUserIdOrderByCreatedAtDesc_pagination() {
        ControllerEntity old = controller(user1, "https://1xstavka.ru/line/football/10");
        old.setCreatedAt(OffsetDateTime.now().minusHours(2));
        ControllerEntity recent = controller(user1, "https://1xstavka.ru/line/football/11");
        recent.setCreatedAt(OffsetDateTime.now().minusHours(1));
        em.persist(old); em.persist(recent); em.flush();

        Page<ControllerEntity> page = controllerRepository
                .findByUserIdOrderByCreatedAtDesc(user1.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        // Most recent first
        assertThat(page.getContent().get(0).getUrl()).isEqualTo(recent.getUrl());
    }

    @Test
    @DisplayName("countByUserIdAndIsActiveTrue: counts only active controllers")
    void countByUserIdAndIsActiveTrue_onlyCountsActive() {
        ControllerEntity active   = controller(user1, "https://1xstavka.ru/line/football/20");
        ControllerEntity inactive = controller(user1, "https://1xstavka.ru/line/football/21");
        inactive.setIsActive(false);
        em.persist(active); em.persist(inactive); em.flush();

        int count = controllerRepository.countByUserIdAndIsActiveTrue(user1.getId());

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("existsByUserIdAndBookmakerAndUrl: returns true for exact match")
    void existsByUserIdAndBookmakerAndUrl_exists() {
        String url = "https://1xstavka.ru/line/football/30";
        em.persistAndFlush(controller(user1, url));

        assertThat(controllerRepository
                .existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(user1.getId(), BookmakerType.XBET, url))
                .isTrue();
    }

    @Test
    @DisplayName("existsByUserIdAndBookmakerAndUrl: returns false for different user")
    void existsByUserIdAndBookmakerAndUrl_differentUser_false() {
        String url = "https://1xstavka.ru/line/football/31";
        em.persistAndFlush(controller(user1, url));

        assertThat(controllerRepository
                .existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(user2.getId(), BookmakerType.XBET, url))
                .isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserEntity user(String username) {
        return UserEntity.builder()
                .telegramId(Math.abs(UUID.randomUUID().getMostSignificantBits()))
                .username(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private ControllerEntity controller(UserEntity owner, String url) {
        return ControllerEntity.builder()
                .user(owner)
                .bookmaker(BookmakerType.XBET)
                .url(url)
                .type(ControllerType.TOURNAMENT)
                .isActive(true)
                .isMuted(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
