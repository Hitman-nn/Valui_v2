package com.valui.betting.service;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonBalanceDto;
import com.valui.betting.repository.BetAccountRepository;
import com.valui.betting.repository.BetPersonBalanceRepository;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.service.impl.BetAccountServiceImpl;
import com.valui.common.entity.BetAccountEntity;
import com.valui.common.entity.BetPersonBalanceEntity;
import com.valui.common.entity.BetPersonEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BetAccountServiceImplTest {

    @Mock BetAccountRepository      repo;
    @Mock BetPersonRepository       personRepo;
    @Mock BetPersonBalanceRepository balanceRepo;

    @InjectMocks BetAccountServiceImpl service;

    // ── create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("успешно создаёт счёт и возвращает DTO с теми же полями")
        void createAccount_success_returnsDtoWithCorrectFields() {
            long chatId = 42L;
            String name = "Основной";
            BetAccountEntity saved = account(chatId, name);

            given(repo.existsByChatIdAndNameIgnoreCase(chatId, name)).willReturn(false);
            given(repo.save(any())).willReturn(saved);

            BetAccountDto result = service.create(chatId, name);

            assertThat(result.chatId()).isEqualTo(chatId);
            assertThat(result.name()).isEqualTo(name);
            assertThat(result.id()).isEqualTo(saved.getId());
        }

        @Test
        @DisplayName("триммит пробелы перед сохранением")
        void createAccount_trimsName() {
            long chatId = 1L;
            given(repo.existsByChatIdAndNameIgnoreCase(eq(chatId), eq("Счёт"))).willReturn(false);
            given(repo.save(any())).willReturn(account(chatId, "Счёт"));

            ArgumentCaptor<BetAccountEntity> cap = ArgumentCaptor.forClass(BetAccountEntity.class);
            service.create(chatId, "  Счёт  ");

            then(repo).should().save(cap.capture());
            assertThat(cap.getValue().getName()).isEqualTo("Счёт");
        }

        @Test
        @DisplayName("дублирующееся название бросает IllegalStateException")
        void createAccount_duplicateName_throwsIllegalState() {
            long chatId = 5L;
            given(repo.existsByChatIdAndNameIgnoreCase(chatId, "Дубль")).willReturn(true);

            assertThatIllegalStateException()
                    .isThrownBy(() -> service.create(chatId, "Дубль"))
                    .withMessageContaining("уже существует");
        }

        @Test
        @DisplayName("пустое название бросает IllegalArgumentException")
        void createAccount_blankName_throwsIllegalArgument() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.create(1L, "   "))
                    .withMessageContaining("пустым");
        }

        @Test
        @DisplayName("null название бросает IllegalArgumentException")
        void createAccount_nullName_throwsIllegalArgument() {
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
        @DisplayName("возвращает только счета данного чата")
        void listForChat_returnsOnlyAccountsOfThatChat() {
            long chatId = 10L;
            BetAccountEntity a1 = account(chatId, "Alpha");
            BetAccountEntity a2 = account(chatId, "Beta");
            given(repo.findAllByChatIdOrderByNameAsc(chatId)).willReturn(List.of(a1, a2));

            List<BetAccountDto> result = service.listForChat(chatId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(BetAccountDto::chatId).containsOnly(chatId);
            assertThat(result).extracting(BetAccountDto::name).containsExactly("Alpha", "Beta");
        }

        @Test
        @DisplayName("возвращает пустой список если счетов нет")
        void listForChat_emptyWhenNoAccounts() {
            given(repo.findAllByChatIdOrderByNameAsc(anyLong())).willReturn(List.of());

            List<BetAccountDto> result = service.listForChat(99L);

            assertThat(result).isEmpty();
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("удаляет свой счёт без ошибок")
        void deleteAccount_ownAccount_deletesSuccessfully() {
            long chatId = 7L;
            BetAccountEntity acct = account(chatId, "Удалить меня");
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));

            service.delete(acct.getId(), chatId);

            then(repo).should().delete(acct);
        }

        @Test
        @DisplayName("счёт не найден — бросает NoSuchElementException")
        void deleteAccount_notFound_throwsNoSuchElement() {
            UUID id = UUID.randomUUID();
            given(repo.findById(id)).willReturn(Optional.empty());

            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> service.delete(id, 1L))
                    .withMessageContaining("не найден");
        }

        @Test
        @DisplayName("попытка удалить чужой счёт бросает SecurityException")
        void deleteAccount_wrongChat_throwsSecurityException() {
            BetAccountEntity acct = account(99L, "Чужой");
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.delete(acct.getId(), 1L))
                    .withMessageContaining("не принадлежит");
        }
    }

    // ── getPersonsWithBalances ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getPersonsWithBalances")
    class GetPersonsWithBalances {

        @Test
        @DisplayName("привязанный участник имеет баланс, непривязанный — null")
        void getPersonsWithBalances_linkedAndNotLinkedPersons() {
            long chatId = 3L;
            BetAccountEntity acct = account(chatId, "Тест");
            BetPersonEntity linked = person(chatId, "Лёша");
            BetPersonEntity notLinked = person(chatId, "Маша");

            BetPersonBalanceEntity balanceEntry = BetPersonBalanceEntity.builder()
                    .id(UUID.randomUUID())
                    .account(acct)
                    .person(linked)
                    .balance(new BigDecimal("150.00"))
                    .updatedAt(OffsetDateTime.now())
                    .build();

            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(personRepo.findAllByChatIdOrderByDisplayNameAsc(chatId))
                    .willReturn(List.of(linked, notLinked));
            given(balanceRepo.findByAccountIdWithPerson(acct.getId()))
                    .willReturn(List.of(balanceEntry));

            List<BetPersonBalanceDto> result = service.getPersonsWithBalances(acct.getId(), chatId);

            assertThat(result).hasSize(2);
            BetPersonBalanceDto linkedDto = result.stream()
                    .filter(d -> d.personId().equals(linked.getId())).findFirst().orElseThrow();
            BetPersonBalanceDto notLinkedDto = result.stream()
                    .filter(d -> d.personId().equals(notLinked.getId())).findFirst().orElseThrow();

            assertThat(linkedDto.balance()).isEqualByComparingTo("150.00");
            assertThat(linkedDto.isLinked()).isTrue();
            assertThat(notLinkedDto.balance()).isNull();
            assertThat(notLinkedDto.isLinked()).isFalse();
        }

        @Test
        @DisplayName("чужой accountId бросает SecurityException")
        void getPersonsWithBalances_wrongChat_throwsSecurityException() {
            BetAccountEntity acct = account(99L, "Чужой");
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.getPersonsWithBalances(acct.getId(), 1L))
                    .withMessageContaining("не принадлежит");
        }
    }

    // ── adjustPersonBalance ───────────────────────────────────────────────────

    @Nested
    @DisplayName("adjustPersonBalance")
    class AdjustPersonBalance {

        @Test
        @DisplayName("создаёт новую запись баланса если не существует и применяет дельту")
        void adjustPersonBalance_newBalance_createsEntry() {
            long chatId = 2L;
            BetAccountEntity acct = account(chatId, "Основной");
            BetPersonEntity p = person(chatId, "Иван");

            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(personRepo.findById(p.getId())).willReturn(Optional.of(p));
            given(balanceRepo.findByAccountIdAndPersonId(acct.getId(), p.getId()))
                    .willReturn(Optional.empty());
            given(balanceRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.adjustPersonBalance(acct.getId(), p.getId(), chatId, new BigDecimal("200.00"));

            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            then(balanceRepo).should().save(cap.capture());
            assertThat(cap.getValue().getBalance()).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("прибавляет дельту к существующему балансу")
        void adjustPersonBalance_existingBalance_addsDelta() {
            long chatId = 2L;
            BetAccountEntity acct = account(chatId, "Основной");
            BetPersonEntity p = person(chatId, "Иван");
            BetPersonBalanceEntity existing = BetPersonBalanceEntity.builder()
                    .id(UUID.randomUUID()).account(acct).person(p)
                    .balance(new BigDecimal("100.00")).updatedAt(OffsetDateTime.now()).build();

            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(personRepo.findById(p.getId())).willReturn(Optional.of(p));
            given(balanceRepo.findByAccountIdAndPersonId(acct.getId(), p.getId()))
                    .willReturn(Optional.of(existing));
            given(balanceRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.adjustPersonBalance(acct.getId(), p.getId(), chatId, new BigDecimal("50.00"));

            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            then(balanceRepo).should().save(cap.capture());
            assertThat(cap.getValue().getBalance()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("участник из чужого чата бросает NoSuchElementException")
        void adjustPersonBalance_personFromWrongChat_throwsNoSuchElement() {
            long chatId = 2L;
            BetAccountEntity acct = account(chatId, "Основной");
            BetPersonEntity foreignPerson = person(999L, "Чужой");

            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(personRepo.findById(foreignPerson.getId())).willReturn(Optional.of(foreignPerson));

            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() -> service.adjustPersonBalance(
                            acct.getId(), foreignPerson.getId(), chatId, BigDecimal.ONE))
                    .withMessageContaining("Участник не найден");
        }
    }

    // ── setPersonBalance ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("setPersonBalance")
    class SetPersonBalance {

        @Test
        @DisplayName("устанавливает баланс напрямую, игнорируя текущее значение")
        void setPersonBalance_overwritesExistingBalance() {
            long chatId = 4L;
            BetAccountEntity acct = account(chatId, "Сет");
            BetPersonEntity p = person(chatId, "Дима");
            BetPersonBalanceEntity existing = BetPersonBalanceEntity.builder()
                    .id(UUID.randomUUID()).account(acct).person(p)
                    .balance(new BigDecimal("999.00")).updatedAt(OffsetDateTime.now()).build();

            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(personRepo.findById(p.getId())).willReturn(Optional.of(p));
            given(balanceRepo.findByAccountIdAndPersonId(acct.getId(), p.getId()))
                    .willReturn(Optional.of(existing));
            given(balanceRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.setPersonBalance(acct.getId(), p.getId(), chatId, new BigDecimal("10.00"));

            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            then(balanceRepo).should().save(cap.capture());
            assertThat(cap.getValue().getBalance()).isEqualByComparingTo("10.00");
        }
    }

    // ── removePersonBalance ───────────────────────────────────────────────────

    @Nested
    @DisplayName("removePersonBalance")
    class RemovePersonBalance {

        @Test
        @DisplayName("вызывает deleteByAccountIdAndPersonId для правильного счёта")
        void removePersonBalance_callsDeleteOnRepo() {
            long chatId = 6L;
            BetAccountEntity acct = account(chatId, "Удалить баланс");
            UUID personId = UUID.randomUUID();
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));

            service.removePersonBalance(acct.getId(), personId, chatId);

            then(balanceRepo).should().deleteByAccountIdAndPersonId(acct.getId(), personId);
        }
    }

    // ── getTotalBalance ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getTotalBalance")
    class GetTotalBalance {

        @Test
        @DisplayName("возвращает сумму всех балансов участников в счёте")
        void getTotalBalance_returnsSumFromRepo() {
            long chatId = 8L;
            BetAccountEntity acct = account(chatId, "Итого");
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));
            given(balanceRepo.sumBalanceByAccountId(acct.getId())).willReturn(new BigDecimal("750.00"));

            BigDecimal total = service.getTotalBalance(acct.getId(), chatId);

            assertThat(total).isEqualByComparingTo("750.00");
        }

        @Test
        @DisplayName("чужой счёт бросает SecurityException")
        void getTotalBalance_wrongChat_throwsSecurityException() {
            BetAccountEntity acct = account(55L, "Чужой");
            given(repo.findById(acct.getId())).willReturn(Optional.of(acct));

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.getTotalBalance(acct.getId(), 1L))
                    .withMessageContaining("не принадлежит");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BetAccountEntity account(long chatId, String name) {
        return BetAccountEntity.builder()
                .id(UUID.randomUUID())
                .chatId(chatId)
                .name(name)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private BetPersonEntity person(long chatId, String displayName) {
        return BetPersonEntity.builder()
                .id(UUID.randomUUID())
                .chatId(chatId)
                .displayName(displayName)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
