package com.valui.betting.service;

import com.valui.betting.dto.*;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.SlipResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BettingService {

    BetDto placeBet(long telegramId, long chatId, CreateBetRequest request);

    BetDto resolveBet(UUID betId, long chatId, BetStatus result);

    BetDto resolveSlip(UUID betId, int slipSortOrder, long chatId, SlipResult result);

    /** Updates actualPayout on a WON bet and adjusts participant balances by the delta. */
    BetDto correctPayout(UUID betId, long chatId, BigDecimal actualPayout);

    BetDto cancelBet(UUID betId, long chatId);

    void deleteBet(UUID betId, long chatId);

    BetDto getBet(UUID betId, long chatId);

    Page<BetDto> listBets(long chatId, BetStatus statusFilter, Pageable pageable);

    BetStatsDto getStats(long chatId);

    BetAccountStatsDto getAccountStats(UUID accountId, long chatId);

    BetPersonStatsDto getPersonStats(UUID personId, long chatId);

    List<BetPersonBalanceDto> getAccountBalances(UUID accountId, long chatId);
}
