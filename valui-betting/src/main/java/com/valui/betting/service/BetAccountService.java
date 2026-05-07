package com.valui.betting.service;

import com.valui.betting.dto.BetAccountDto;
import com.valui.betting.dto.BetPersonBalanceDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BetAccountService {
    BetAccountDto create(long chatId, String name);
    List<BetAccountDto> listForChat(long chatId);
    void delete(UUID accountId, long chatId);

    /** Returns all persons in the chat with their balance in this account (null balance = not linked). */
    List<BetPersonBalanceDto> getPersonsWithBalances(UUID accountId, long chatId);

    /** Sets (creates or updates) a person's absolute balance in an account. */
    void setPersonBalance(UUID accountId, UUID personId, long chatId, BigDecimal balance);

    /** Adjusts a person's balance by delta (positive = deposit, negative = withdrawal). */
    void adjustPersonBalance(UUID accountId, UUID personId, long chatId, BigDecimal delta);

    /** Removes a person from an account (deletes their balance entry). */
    void removePersonBalance(UUID accountId, UUID personId, long chatId);

    /** Returns the sum of all person balances in an account. */
    java.math.BigDecimal getTotalBalance(UUID accountId, long chatId);
}
