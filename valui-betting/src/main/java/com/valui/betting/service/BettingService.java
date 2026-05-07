package com.valui.betting.service;

import com.valui.betting.dto.*;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.SlipResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface BettingService {

    BetDto placeBet(long telegramId, long chatId, CreateBetRequest request);

    BetDto resolveBet(UUID betId, long telegramId, BetStatus result);

    BetDto resolveSlip(UUID betId, int slipSortOrder, long telegramId, SlipResult result);

    BetDto cancelBet(UUID betId, long telegramId);

    void deleteBet(UUID betId, long chatId);

    BetDto getBet(UUID betId, long chatId);

    Page<BetDto> listBets(long chatId, BetStatus statusFilter, Pageable pageable);

    BetStatsDto getStats(long chatId);

    BetAccountStatsDto getAccountStats(UUID accountId, long chatId);

    BetPersonStatsDto getPersonStats(UUID personId, long chatId);

    List<BetPersonBalanceDto> getAccountBalances(UUID accountId, long chatId);
}
