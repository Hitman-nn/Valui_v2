package com.valui.betting.service.impl;

import com.valui.betting.dto.BankAccountDto;
import com.valui.betting.repository.BankAccountRepository;
import com.valui.betting.repository.BetParticipantRepository;
import com.valui.betting.repository.ChatMemberRepository;
import com.valui.betting.service.BankAccountService;
import com.valui.common.entity.BankAccountEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    private final BankAccountRepository    repo;
    private final BetParticipantRepository participantRepo;
    private final ChatMemberRepository     chatMemberRepo;

    @Override
    @Transactional
    public BankAccountDto createAccount(long ownerTelegramId, String name, String currency) {
        if (repo.existsByOwnerTelegramId(ownerTelegramId)) {
            throw new IllegalStateException("У этого участника уже есть счёт");
        }
        BankAccountEntity account = BankAccountEntity.builder()
                .ownerTelegramId(ownerTelegramId)
                .name(name)
                .currency(currency != null ? currency : "RUB")
                .balance(BigDecimal.ZERO)
                .isDefault(true)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return BankAccountDto.from(repo.save(account));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsForUser(long telegramId) {
        return repo.existsByOwnerTelegramId(telegramId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BankAccountDto> findForUser(long telegramId) {
        return repo.findByOwnerTelegramId(telegramId).map(BankAccountDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankAccountDto> listAccountsForChat(long chatId, long currentUserId) {
        Set<Long> tids = new LinkedHashSet<>();

        // 1. All tracked chat members (populated as users interact in the group)
        chatMemberRepo.findAllByChatIdOrderByFirstNameAsc(chatId)
                .forEach(m -> tids.add(m.getTelegramId()));

        // 2. Participants from past bets (fallback when chat_members not yet populated)
        participantRepo.findDistinctParticipantsByChatId(chatId)
                .forEach(row -> tids.add((Long) row[0]));

        // 3. Always include current user
        tids.add(currentUserId);

        return repo.findAllByOwnerTelegramIdInOrderByNameAsc(tids)
                .stream().map(BankAccountDto::from).toList();
    }

    @Override
    @Transactional
    public BankAccountDto setDefault(long telegramId, UUID accountId) {
        BankAccountEntity account = requireOwned(telegramId, accountId);
        repo.clearDefaultExcept(telegramId, accountId);
        account.setIsDefault(true);
        account.setUpdatedAt(OffsetDateTime.now());
        return BankAccountDto.from(repo.save(account));
    }

    @Override
    @Transactional
    public BankAccountDto adjustBalance(long telegramId, UUID accountId, BigDecimal delta) {
        BankAccountEntity account = requireOwned(telegramId, accountId);
        account.setBalance(account.getBalance().add(delta));
        account.setUpdatedAt(OffsetDateTime.now());
        return BankAccountDto.from(repo.save(account));
    }

    @Override
    @Transactional
    public void deleteAccount(long telegramId, UUID accountId) {
        repo.delete(requireOwned(telegramId, accountId));
    }

    @Override
    @Transactional
    public void deleteAccountById(UUID accountId) {
        if (!repo.existsById(accountId)) {
            throw new NoSuchElementException("Счёт не найден: " + accountId);
        }
        repo.deleteById(accountId);
    }

    private BankAccountEntity requireOwned(long telegramId, UUID accountId) {
        BankAccountEntity account = repo.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Счёт не найден: " + accountId));
        if (!account.getOwnerTelegramId().equals(telegramId)) {
            throw new SecurityException("Счёт " + accountId + " не принадлежит пользователю " + telegramId);
        }
        return account;
    }
}
