package com.valui.betting.service;

import com.valui.betting.dto.BankAccountDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountService {

    /** Creates an account for the given owner. Throws if one already exists (unique per person). */
    BankAccountDto createAccount(long ownerTelegramId, String name, String currency);

    boolean existsForUser(long telegramId);

    Optional<BankAccountDto> findForUser(long telegramId);

    /** All accounts belonging to participants who appeared in bets in the given chat, plus currentUserId. */
    List<BankAccountDto> listAccountsForChat(long chatId, long currentUserId);

    BankAccountDto setDefault(long telegramId, UUID accountId);

    /** Adjusts balance by delta (positive = deposit, negative = withdrawal). */
    BankAccountDto adjustBalance(long telegramId, UUID accountId, BigDecimal delta);

    void deleteAccount(long telegramId, UUID accountId);

    /** Deletes without ownership check — for group-chat admin flows. */
    void deleteAccountById(UUID accountId);
}
