package com.valui.monitor.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.domain.UserRole;
import com.valui.common.domain.UserStatus;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.user.api.ControllerPortService;
import com.valui.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ControllersJsonMigrator — unit tests")
class ControllersJsonMigratorTest {

    @Mock ControllerPortService controllerPort;
    @Mock UserService userService;

    ControllersJsonMigrator migrator;

    @TempDir Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final long TG_ID = 123456789L;
    private static final String XBET_URL = "https://1xstavka.ru/line/football/12345";

    @BeforeEach
    void setUp() {
        migrator = new ControllersJsonMigrator(controllerPort, userService, mapper);
    }

    @Test
    @DisplayName("migrate: file not found — does nothing")
    void migrate_fileNotFound_skips() {
        setPath(tempDir.resolve("nonexistent.json").toString());

        migrator.migrate();

        verify(controllerPort, never()).save(any());
    }

    @Test
    @DisplayName("migrate: .migrated file present — skips")
    void migrate_alreadyMigrated_skips() throws IOException {
        Path source  = tempDir.resolve("controllers.json");
        Path migrated = tempDir.resolve("controllers.json.migrated");
        Files.writeString(source, "{}");
        Files.writeString(migrated, "{}");
        setPath(source.toString());

        migrator.migrate();

        verify(controllerPort, never()).save(any());
    }

    @Test
    @DisplayName("migrate: valid entry — imports controller and renames file")
    void migrate_validEntry_importsAndRenames() throws IOException {
        Path source = tempDir.resolve("controllers.json");
        Files.writeString(source, buildJson(TG_ID, XBET_URL, "Premier League", null));
        setPath(source.toString());

        UserEntity user = user(TG_ID);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.existsByUserAndBookmakerAndUrl(
                any(), eq(BookmakerType.XBET), eq(XBET_URL))).willReturn(false);
        given(controllerPort.save(any())).willAnswer(inv -> {
            ControllerEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID()); return e;
        });

        migrator.migrate();

        ArgumentCaptor<ControllerEntity> captor = ArgumentCaptor.forClass(ControllerEntity.class);
        verify(controllerPort).save(captor.capture());
        ControllerEntity saved = captor.getValue();
        assertThat(saved.getBookmaker()).isEqualTo(BookmakerType.XBET);
        assertThat(saved.getUrl()).isEqualTo(XBET_URL);
        assertThat(saved.getTitle()).isEqualTo("Premier League");
        assertThat(saved.getType()).isEqualTo(ControllerType.TOURNAMENT);
        assertThat(saved.getIsActive()).isTrue();

        // source file renamed to .migrated
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.exists(tempDir.resolve("controllers.json.migrated"))).isTrue();
    }

    @Test
    @DisplayName("migrate: duplicate entry — skipped (idempotent)")
    void migrate_duplicate_skipped() throws IOException {
        Path source = tempDir.resolve("controllers.json");
        Files.writeString(source, buildJson(TG_ID, XBET_URL, null, null));
        setPath(source.toString());

        UserEntity user = user(TG_ID);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.existsByUserAndBookmakerAndUrl(any(), any(), any())).willReturn(true);

        migrator.migrate();

        verify(controllerPort, never()).save(any());
        // file is still renamed even if nothing was imported
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    @DisplayName("migrate: user not found — entry skipped, file renamed")
    void migrate_userNotFound_entrySkipped() throws IOException {
        Path source = tempDir.resolve("controllers.json");
        Files.writeString(source, buildJson(TG_ID, XBET_URL, null, null));
        setPath(source.toString());

        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.empty());

        migrator.migrate();

        verify(controllerPort, never()).save(any());
        assertThat(Files.exists(tempDir.resolve("controllers.json.migrated"))).isTrue();
    }

    @Test
    @DisplayName("migrate: filter rule is preserved")
    void migrate_filterRule_preserved() throws IOException {
        Path source = tempDir.resolve("controllers.json");
        Files.writeString(source, buildJson(TG_ID, XBET_URL, "EPL", ".*Liverpool.*"));
        setPath(source.toString());

        UserEntity user = user(TG_ID);
        given(userService.findByTelegramId(TG_ID)).willReturn(Optional.of(user));
        given(controllerPort.existsByUserAndBookmakerAndUrl(any(), any(), any())).willReturn(false);
        given(controllerPort.save(any())).willAnswer(inv -> {
            ControllerEntity e = inv.getArgument(0); e.setId(UUID.randomUUID()); return e;
        });

        migrator.migrate();

        ArgumentCaptor<ControllerEntity> captor = ArgumentCaptor.forClass(ControllerEntity.class);
        verify(controllerPort).save(captor.capture());
        assertThat(captor.getValue().getFilterRule()).isEqualTo(".*Liverpool.*");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setPath(String path) {
        ReflectionTestUtils.setField(migrator, "controllersJsonPath", path);
    }

    private String buildJson(long chatId, String link, String title, String ruleFilter) throws IOException {
        Map<String, Object> controller = new java.util.LinkedHashMap<>();
        controller.put("chatid", String.valueOf(chatId));
        controller.put("link", link);
        controller.put("title", title);
        controller.put("ruleFilter", ruleFilter);
        return mapper.writeValueAsString(Map.of("filter", List.of(), "controller", List.of(controller)));
    }

    private UserEntity user(long telegramId) {
        return UserEntity.builder()
                .id(UUID.randomUUID()).telegramId(telegramId)
                .role(UserRole.USER).status(UserStatus.ACTIVE)
                .build();
    }
}
