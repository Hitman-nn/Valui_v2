package com.valui.betting.service.impl;

import com.valui.betting.dto.*;
import com.valui.betting.repository.BankAccountRepository;
import com.valui.betting.repository.BetRepository;
import com.valui.betting.service.BettingService;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import com.valui.common.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class BettingServiceImpl implements BettingService {

    private final BetRepository betRepo;
    private final BankAccountRepository bankRepo;

    @Override
    @Transactional
    public BetDto placeBet(long telegramId, long chatId, CreateBetRequest req) {
        BigDecimal totalOdds = computeTotalOdds(req.slips());
        BigDecimal potential = req.totalStake().multiply(totalOdds).setScale(2, RoundingMode.HALF_UP);

        BetEntity bet = BetEntity.builder()
                .telegramId(telegramId)
                .chatId(chatId)
                .type(req.type())
                .status(BetStatus.OPEN)
                .totalOdds(totalOdds)
                .totalStake(req.totalStake())
                .potentialPayout(potential)
                .slips(new ArrayList<>())
                .participants(new ArrayList<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        AtomicInteger order = new AtomicInteger(0);
        for (BetSlipRequest s : req.slips()) {
            BetSlipEntity slip = BetSlipEntity.builder()
                    .bet(bet)
                    .matchTitle(s.matchTitle())
                    .matchUrl(s.matchUrl())
                    .bookmaker(s.bookmaker())
                    .odds(s.odds())
                    .result(SlipResult.OPEN)
                    .sortOrder(order.getAndIncrement())
                    .build();
            bet.getSlips().add(slip);
        }

        for (ParticipantRequest p : req.participants()) {
            BankAccountEntity bankAccount = resolveAccount(telegramId, p);
            BetParticipantEntity participant = BetParticipantEntity.builder()
                    .bet(bet)
                    .telegramId(p.telegramId())
                    .displayName(p.displayName())
                    .stake(p.stake())
                    .profitShare(p.profitShare() != null ? p.profitShare() : BigDecimal.ONE)
                    .bankAccount(bankAccount)
                    .build();
            bet.getParticipants().add(participant);

            // Deduct stake from bank account immediately
            if (bankAccount != null) {
                bankAccount.setBalance(bankAccount.getBalance().subtract(p.stake()));
                bankAccount.setUpdatedAt(OffsetDateTime.now());
                bankRepo.save(bankAccount);
            }
        }

        return BetDto.from(betRepo.save(bet));
    }

    @Override
    @Transactional
    public BetDto resolveBet(UUID betId, long telegramId, BetStatus result) {
        BetEntity bet = requireAccessible(betId, telegramId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Bet is already resolved: " + bet.getStatus());
        }

        bet.setStatus(result);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());

        // Mark all slips with the corresponding result
        SlipResult slipResult = switch (result) {
            case WON      -> SlipResult.WON;
            case LOST     -> SlipResult.LOST;
            case RETURNED -> SlipResult.RETURNED;
            default       -> SlipResult.OPEN;
        };
        bet.getSlips().forEach(s -> {
            s.setResult(slipResult);
            s.setResolvedAt(bet.getResolvedAt());
        });

        // Compute actual payout
        BigDecimal actualPayout = switch (result) {
            case WON      -> bet.getTotalStake().multiply(bet.getTotalOdds()).setScale(2, RoundingMode.HALF_UP);
            case RETURNED -> bet.getTotalStake();
            default       -> BigDecimal.ZERO;
        };
        bet.setActualPayout(actualPayout);

        // Update bank accounts for each participant
        for (BetParticipantEntity p : bet.getParticipants()) {
            if (p.getBankAccount() == null) continue;
            BigDecimal participantPayout = actualPayout
                    .multiply(p.getProfitShare())
                    .setScale(2, RoundingMode.HALF_UP);
            BankAccountEntity acct = p.getBankAccount();
            acct.setBalance(acct.getBalance().add(participantPayout));
            acct.setUpdatedAt(OffsetDateTime.now());
            bankRepo.save(acct);
        }

        return BetDto.from(betRepo.save(bet));
    }

    @Override
    @Transactional
    public BetDto cancelBet(UUID betId, long telegramId) {
        BetEntity bet = requireAccessible(betId, telegramId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Only open bets can be cancelled");
        }

        bet.setStatus(BetStatus.CANCELLED);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());
        bet.setActualPayout(BigDecimal.ZERO);

        // Refund stake to bank accounts
        for (BetParticipantEntity p : bet.getParticipants()) {
            if (p.getBankAccount() == null) continue;
            BankAccountEntity acct = p.getBankAccount();
            acct.setBalance(acct.getBalance().add(p.getStake()));
            acct.setUpdatedAt(OffsetDateTime.now());
            bankRepo.save(acct);
        }

        return BetDto.from(betRepo.save(bet));
    }

    @Override
    @Transactional(readOnly = true)
    public BetDto getBet(UUID betId, long telegramId) {
        return BetDto.from(requireAccessible(betId, telegramId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BetDto> listBets(long telegramId, BetStatus statusFilter, Pageable pageable) {
        Page<BetEntity> page = statusFilter != null
                ? betRepo.findAllByTelegramIdAndStatusOrderByCreatedAtDesc(telegramId, statusFilter, pageable)
                : betRepo.findAllByTelegramIdOrderByCreatedAtDesc(telegramId, pageable);
        return page.map(BetDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public BetStatsDto getStats(long telegramId) {
        long total    = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.OPEN)
                      + betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.WON)
                      + betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.LOST)
                      + betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.RETURNED);
        long open     = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.OPEN);
        long won      = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.WON);
        long lost     = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.LOST);
        long returned = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.RETURNED);

        BigDecimal staked = betRepo.sumTotalStakeByTelegramId(telegramId);
        BigDecimal payout = betRepo.sumActualPayoutByTelegramId(telegramId);
        BigDecimal pl     = payout.subtract(staked);
        double roi = staked.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : pl.divide(staked, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                     .round(new MathContext(4)).doubleValue();

        return new BetStatsDto(total, open, won, lost, returned, staked, payout, pl, roi);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal computeTotalOdds(List<BetSlipRequest> slips) {
        BigDecimal odds = BigDecimal.ONE;
        for (BetSlipRequest s : slips) {
            odds = odds.multiply(s.odds());
        }
        return odds.setScale(4, RoundingMode.HALF_UP);
    }

    private BetEntity requireAccessible(UUID betId, long telegramId) {
        BetEntity bet = betRepo.findWithDetailById(betId)
                .orElseThrow(() -> new NoSuchElementException("Bet not found: " + betId));
        if (bet.getTelegramId() != telegramId
                && bet.getParticipants().stream().noneMatch(p -> p.getTelegramId() == telegramId)) {
            throw new SecurityException("Bet " + betId + " is not accessible to user " + telegramId);
        }
        return bet;
    }

    private BankAccountEntity resolveAccount(long ownerTelegramId, ParticipantRequest p) {
        if (p.bankAccountId() != null) {
            return bankRepo.findById(p.bankAccountId()).orElse(null);
        }
        // One account per person — find it directly
        return bankRepo.findByOwnerTelegramId(p.telegramId()).orElse(null);
    }
}
