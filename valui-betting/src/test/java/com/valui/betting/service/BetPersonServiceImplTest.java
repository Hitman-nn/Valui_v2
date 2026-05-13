package com.valui.betting.service;

import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.service.impl.BetPersonServiceImpl;
import com.valui.common.entity.BetPersonEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BetPersonServiceImplTest {

    @Mock BetPersonRepository repo;

    @InjectMocks BetPersonServiceImpl service;

    // ── create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("успешно создаёт участника и возвращает DTO с правильными полями")
        void createPerson_success_returnsDtoWithCorrectFields() {
            long chatId = 10L;
            String displayName = "Алексей";
            BetPersonEntity saved = person(chatId, displayName);

            given(repo.existsByChatIdAndDisplayNameIgnoreCase(chatId, displayName)).willReturn(false);
            given(repo.save(any())).willReturn(saved);

            BetPersonDto result = service.create(chatId, displayName);

            assertThat(result.chatId()).isEqualTo(chatId);
            assertThat(result.displayName()).isEqualTo(displayName);
            assertThat(result.id()).isEqualTo(saved.getId());
        }

        @Test
        @DisplayName("триммит пробелы в имени перед сохранением")
        void createPerson_trimsDisplayName() {
            long chatId = 1L;
            given(repo.existsByChatIdAndDisplayNameIgnoreCase(eq(chatId), eq("Боря"))).willReturn(false);
            given(repo.save(any())).willReturn(person(chatId, "Боря"));

            ArgumentCaptor<BetPersonEntity> cap = ArgumentCaptor.forClass(BetPersonEntity.class);
            service.create(chatId, "  Боря  ");

            then(repo).should().save(cap.capture());
            assertThat(cap.getValue().getDisplayName()).isEqualTo("Боря");
        }

        @Test
        @DisplayName("дублирующееся имя бросает IllegalStateException")
        void createPerson_duplicateName_throwsIllegalState() {
            long chatId = 5L;
            given(repo.existsByChatIdAndDisplayNameIgnoreCase(chatId, "Дубль")).willReturn(true);

            assertThatIllegalStateException()
                    .isThrownBy(() -> service.create(chatId, "Дубль"))
                    .withMessageContaining("уже существует");
        }

        @Test
        @DisplayName("пустое имя бросает IllegalArgumentException")
        void createPerson_blankName_throwsIllegalArgument() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.create(1L, "   "))
                    .withMessageContaining("пустым");
        }

        @Test
        @DisplayName("null имя бросает IllegalArgumentException")
        void createPerson_nullName_throwsIllegalArgument() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.create(1L, null))
                    .withMessageContaining("пустым");
        }
    }

    // ── listForChat ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listForChat")
    class ListForChat {

        @Test
        @DisplayName("возвращает всех участников данного чата в алфавитном порядке")
        void listForChat_returnsParticipantsOfThatChat() {
            long chatId = 20L;
            BetPersonEntity p1 = person(chatId, "Аня");
            BetPersonEntity p2 = person(chatId, "Боря");
            given(repo.findAllByChatIdOrderByDisplayNameAsc(chatId)).willReturn(List.of(p1, p2));

            List<BetPersonDto> result = service.listForChat(chatId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(BetPersonDto::chatId).containsOnly(chatId);
            assertThat(result).extracting(BetPersonDto::displayName).containsExactly("Аня", "Боря");
        }

        @Test
        @DisplayName("возвращает пустой список если участников нет")
        void listForChat_emptyWhenNoPersons() {
            given(repo.findAllByChatIdOrderByDisplayNameAsc(anyLong())).willReturn(List.of());

            List<BetPersonDto> result = service.listForChat(99L);

            assertThat(result).isEmpty();
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("удаляет своего участника без ошибок")
        void deletePerson_ownPerson_deletesSuccessfully() {
            long chatId = 7L;
            BetPersonEntity p = person(chatId, "Удалить");
            given(repo.findById(p.getId())).willReturn(Optional.of(p));

            service.delete(p.getId(), chatId);

            then(repo).should().delete(p);
        }

        @Test
        @DisplayName("участник не найден — бросает NoSuchElementException")
        void deletePerson_notFound_throwsNoSuchElement() {
            UUID id = UUID.randomUUID();
            given(repo.findById(id)).willReturn(Optional.empty());

            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> service.delete(id, 1L))
                    .withMessageContaining("не найден");
        }

        @Test
        @DisplayName("попытка удалить участника чужого чата бросает SecurityException")
        void deletePerson_wrongChat_throwsSecurityException() {
            BetPersonEntity p = person(99L, "Чужой");
            given(repo.findById(p.getId())).willReturn(Optional.of(p));

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.delete(p.getId(), 1L))
                    .withMessageContaining("не принадлежит");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BetPersonEntity person(long chatId, String displayName) {
        return BetPersonEntity.builder()
                .id(UUID.randomUUID())
                .chatId(chatId)
                .displayName(displayName)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
