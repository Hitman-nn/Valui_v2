package com.valui.betting.service.impl;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonBalanceDto;
import com.valui.betting.repository.BetAccountRepository;
import com.valui.betting.repository.BetPersonBalanceRepository;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.service.BetAccountService;
import com.valui.common.entity.BetAccountEntity;
import com.valui.common.entity.BetPersonBalanceEntity;
import com.valui.common.entity.BetPersonEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BetAccountServiceImpl implements BetAccountService {

    private final BetAccountRepository      repo;
    private final BetPersonRepository       personRepo;
    private final BetPersonBalanceRepository balanceRepo;

    @Override
    @Transactional
    public BetAccountDto create(long chatId, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Название счёта не может быть пустым");
        if (repo.existsByChatIdAndNameIgnoreCase(chatId, name.trim())) {
            throw new IllegalStateException("Счёт с таким названием уже существует");
        }
        OffsetDateTime now = OffsetDateTime.now();
        BetAccountEntity entity = BetAccountEntity.builder()
                .chatId(chatId)
                .name(name.trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return BetAccountDto.from(repo.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BetAccountDto> listForChat(long chatId) {
        return repo.findAllByChatIdOrderByNameAsc(chatId)
                .stream().map(BetAccountDto::from).toList();
    }

    @Override
    @Transactional
    public void delete(UUID accountId, long chatId) {
        BetAccountEntity e = requireAccount(accountId, chatId);
        repo.delete(e);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BetPersonBalanceDto> getPersonsWithBalances(UUID accountId, long chatId) {
        requireAccount(accountId, chatId);

        List<BetPersonEntity> allPersons = personRepo.findAllByChatIdOrderByDisplayNameAsc(chatId);
        Map<UUID, BigDecimal> linked = balanceRepo.findByAccountIdWithPerson(accountId)
                .stream()
                .collect(Collectors.toMap(b -> b.getPerson().getId(), BetPersonBalanceEntity::getBalance));

        List<BetPersonBalanceDto> result = new ArrayList<>(allPersons.size());
        for (BetPersonEntity p : allPersons) {
            BigDecimal balance = linked.get(p.getId());
            result.add(balance != null
                    ? new BetPersonBalanceDto(p.getId(), p.getDisplayName(), balance)
                    : BetPersonBalanceDto.notLinked(p.getId(), p.getDisplayName()));
        }
        return result;
    }

    @Override
    @Transactional
    public void adjustPersonBalance(UUID accountId, UUID personId, long chatId, BigDecimal delta) {
        BetAccountEntity account = requireAccount(accountId, chatId);
        BetPersonEntity person = personRepo.findById(personId)
                .filter(p -> p.getChatId().equals(chatId))
                .orElseThrow(() -> new NoSuchElementException("Участник не найден"));
        BetPersonBalanceEntity bal = balanceRepo.findByAccountIdAndPersonId(accountId, personId)
                .orElseGet(() -> BetPersonBalanceEntity.builder()
                        .account(account).person(person)
                        .balance(BigDecimal.ZERO).updatedAt(OffsetDateTime.now())
                        .build());
        bal.setBalance(bal.getBalance().add(delta));
        bal.setUpdatedAt(OffsetDateTime.now());
        balanceRepo.save(bal);
    }

    @Override
    @Transactional
    public void setPersonBalance(UUID accountId, UUID personId, long chatId, BigDecimal balance) {
        BetAccountEntity account = requireAccount(accountId, chatId);
        BetPersonEntity person = personRepo.findById(personId)
                .filter(p -> p.getChatId().equals(chatId))
                .orElseThrow(() -> new NoSuchElementException("Участник не найден"));

        BetPersonBalanceEntity bal = balanceRepo.findByAccountIdAndPersonId(accountId, personId)
                .orElseGet(() -> BetPersonBalanceEntity.builder()
                        .account(account).person(person)
                        .balance(BigDecimal.ZERO).updatedAt(OffsetDateTime.now())
                        .build());
        bal.setBalance(balance);
        bal.setUpdatedAt(OffsetDateTime.now());
        balanceRepo.save(bal);
    }

    @Override
    @Transactional
    public void removePersonBalance(UUID accountId, UUID personId, long chatId) {
        requireAccount(accountId, chatId);
        balanceRepo.deleteByAccountIdAndPersonId(accountId, personId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.math.BigDecimal getTotalBalance(UUID accountId, long chatId) {
        requireAccount(accountId, chatId);
        return balanceRepo.sumBalanceByAccountId(accountId);
    }

    private BetAccountEntity requireAccount(UUID accountId, long chatId) {
        BetAccountEntity e = repo.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Счёт не найден"));
        if (!e.getChatId().equals(chatId)) throw new SecurityException("Счёт не принадлежит этому чату");
        return e;
    }
}
