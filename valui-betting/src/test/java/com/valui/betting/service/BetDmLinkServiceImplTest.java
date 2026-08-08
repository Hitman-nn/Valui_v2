package com.valui.betting.service;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.repository.BetDmLinkRepository;
import com.valui.betting.service.impl.BetDmLinkServiceImpl;
import com.valui.common.entity.BetDmLinkEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BetDmLinkServiceImplTest {

    @Mock BetDmLinkRepository repo;

    @InjectMocks BetDmLinkServiceImpl service;

    @Nested
    @DisplayName("link")
    class Link {

        @Test
        @DisplayName("новая привязка создаёт запись с указанным названием чата")
        void link_newLink_savesEntity() {
            given(repo.findByChatIdAndTelegramId(-100L, 555L)).willReturn(Optional.empty());

            service.link(-100L, 555L, "Дружеский тотализатор");

            ArgumentCaptor<BetDmLinkEntity> captor = ArgumentCaptor.forClass(BetDmLinkEntity.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getChatId()).isEqualTo(-100L);
            assertThat(captor.getValue().getTelegramId()).isEqualTo(555L);
            assertThat(captor.getValue().getChatTitle()).isEqualTo("Дружеский тотализатор");
        }

        @Test
        @DisplayName("повторная привязка обновляет название чата на существующей записи")
        void link_existingLink_updatesChatTitle() {
            BetDmLinkEntity existing = BetDmLinkEntity.builder()
                    .chatId(-100L).telegramId(555L).chatTitle("Старое имя").build();
            given(repo.findByChatIdAndTelegramId(-100L, 555L)).willReturn(Optional.of(existing));

            service.link(-100L, 555L, "Новое имя");

            ArgumentCaptor<BetDmLinkEntity> captor = ArgumentCaptor.forClass(BetDmLinkEntity.class);
            verify(repo).save(captor.capture());
            assertThat(captor.getValue().getChatTitle()).isEqualTo("Новое имя");
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("удаляет привязку по chatId+telegramId")
        void unlink_deletesByCompositeKey() {
            service.unlink(-100L, 555L);
            verify(repo).deleteByChatIdAndTelegramId(-100L, 555L);
        }
    }

    @Nested
    @DisplayName("isLinked")
    class IsLinked {

        @Test
        @DisplayName("делегирует в repository.existsByChatIdAndTelegramId")
        void isLinked_delegatesToRepo() {
            given(repo.existsByChatIdAndTelegramId(-100L, 555L)).willReturn(true);
            assertThat(service.isLinked(-100L, 555L)).isTrue();
        }
    }

    @Nested
    @DisplayName("listLinks")
    class ListLinks {

        @Test
        @DisplayName("возвращает DTO для всех привязок пользователя")
        void listLinks_mapsEntitiesToDtos() {
            BetDmLinkEntity a = BetDmLinkEntity.builder().chatId(-100L).telegramId(555L).chatTitle("Группа A").build();
            BetDmLinkEntity b = BetDmLinkEntity.builder().chatId(-200L).telegramId(555L).chatTitle("Группа B").build();
            given(repo.findAllByTelegramIdOrderByChatTitleAsc(555L)).willReturn(List.of(a, b));

            List<BetDmLinkDto> result = service.listLinks(555L);

            assertThat(result).containsExactly(
                    new BetDmLinkDto(-100L, "Группа A"),
                    new BetDmLinkDto(-200L, "Группа B"));
        }

        @Test
        @DisplayName("нет привязок → пустой список")
        void listLinks_noLinks_returnsEmpty() {
            given(repo.findAllByTelegramIdOrderByChatTitleAsc(555L)).willReturn(List.of());
            assertThat(service.listLinks(555L)).isEmpty();
        }
    }
}
