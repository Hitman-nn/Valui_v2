package com.valui.betting.service;

import com.valui.betting.dto.BetDto;
import com.valui.betting.dto.BetStatsDto;
import com.valui.betting.dto.CreateBetRequest;
import com.valui.common.domain.BetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface BettingService {

    BetDto placeBet(long telegramId, long chatId, CreateBetRequest request);

    BetDto resolveBet(UUID betId, long telegramId, BetStatus result);

    BetDto cancelBet(UUID betId, long telegramId);

    BetDto getBet(UUID betId, long telegramId);

    Page<BetDto> listBets(long telegramId, BetStatus statusFilter, Pageable pageable);

    BetStatsDto getStats(long telegramId);
}
