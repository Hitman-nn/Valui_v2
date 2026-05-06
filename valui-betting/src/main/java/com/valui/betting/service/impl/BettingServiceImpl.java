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

@Service
@RequiredArgsConstructor
public class BettingServiceImpl implements BettingService {

    // Tolerances for participant share/stake sum validation (rounding accumulation)
    private static final BigDecimal SHARE_SUM_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal STAKE_SUM_TOLERANCE = BigDecimal.ONE;

    private final BetRepository betRepo;
    private final BankAccountRepository bankRepo;

    @Override
    @Transactional
    public BetDto placeBet(long telegramId, long chatId, CreateBetRequest req) {
        if (req.slips() == null || req.slips().isEmpty()) {
            throw new IllegalArgumentException("Ставка должна содержать хотя бы одно событие");
        }
        if (req.type() == BetType.SINGLE && req.slips().size() != 1) {
            throw new IllegalArgumentException("Одиночная ставка должна содержать ровно одно событие");
        }
        if (req.type() == BetType.EXPRESS && req.slips().size() < 2) {
            throw new IllegalArgumentException("Экспресс должен содержать минимум два события");
        }
        if (req.participants() == null || req.participants().isEmpty()) {
            throw new IllegalArgumentException("Необходимо указать хотя бы одного участника");
        }

        List<ParticipantRequest> participants = normalizeParticipants(req.participants(), req.totalStake());

        BigDecimal totalOdds = computeTotalOdds(req.slips());
        BigDecimal potential = req.totalStake().multiply(totalOdds).setScale(2, RoundingMode.HALF_UP);
        OffsetDateTime now = OffsetDateTime.now();

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
                .createdAt(now)
                .updatedAt(now)
                .build();

        int order = 0;
        for (BetSlipRequest s : req.slips()) {
            BetSlipEntity slip = BetSlipEntity.builder()
                    .bet(bet)
                    .matchTitle(s.matchTitle())
                    .matchUrl(s.matchUrl())
                    .bookmaker(s.bookmaker())
                    .odds(s.odds())
                    .result(SlipResult.OPEN)
                    .sortOrder(order++)
                    .build();
            bet.getSlips().add(slip);
        }

        for (ParticipantRequest p : participants) {
            BankAccountEntity bankAccount = resolveAccount(p);
            BetParticipantEntity participant = BetParticipantEntity.builder()
                    .bet(bet)
                    .telegramId(p.telegramId())
                    .displayName(p.displayName())
                    .stake(p.stake())
                    .profitShare(p.profitShare())
                    .bankAccount(bankAccount)
                    .build();
            bet.getParticipants().add(participant);

            if (bankAccount != null) {
                adjustBalance(bankAccount, p.stake().negate());
            }
        }

        return BetDto.from(betRepo.save(bet));
    }

    @Override
    @Transactional
    public BetDto resolveBet(UUID betId, long telegramId, BetStatus result) {
        if (result != BetStatus.WON && result != BetStatus.LOST && result != BetStatus.RETURNED) {
            throw new IllegalArgumentException("Недопустимый статус для завершения ставки: " + result);
        }

        BetEntity bet = requireAccessible(betId, telegramId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Ставка уже завершена: " + bet.getStatus());
        }

        bet.setStatus(result);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());

        SlipResult slipResult;
        BigDecimal actualPayout;
        switch (result) {
            case WON -> {
                slipResult = SlipResult.WON;
                actualPayout = bet.getTotalStake().multiply(bet.getTotalOdds()).setScale(2, RoundingMode.HALF_UP);
            }
            case RETURNED -> {
                slipResult = SlipResult.RETURNED;
                actualPayout = bet.getTotalStake();
            }
            case LOST -> {
                slipResult = SlipResult.LOST;
                actualPayout = BigDecimal.ZERO;
            }
            default -> throw new IllegalArgumentException("Unexpected status: " + result);
        }

        bet.getSlips().forEach(s -> {
            s.setResult(slipResult);
            s.setResolvedAt(bet.getResolvedAt());
        });
        bet.setActualPayout(actualPayout);

        for (BetParticipantEntity p : bet.getParticipants()) {
            if (p.getBankAccount() == null) continue;
            BigDecimal participantPayout = actualPayout
                    .multiply(p.getProfitShare())
                    .setScale(2, RoundingMode.HALF_UP);
            adjustBalance(p.getBankAccount(), participantPayout);
        }

        return BetDto.from(betRepo.save(bet));
    }

    @Override
    @Transactional
    public BetDto cancelBet(UUID betId, long telegramId) {
        BetEntity bet = requireAccessible(betId, telegramId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Отменить можно только открытую ставку");
        }

        bet.setStatus(BetStatus.CANCELLED);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());
        bet.setActualPayout(BigDecimal.ZERO);

        for (BetParticipantEntity p : bet.getParticipants()) {
            if (p.getBankAccount() == null) continue;
            adjustBalance(p.getBankAccount(), p.getStake());
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
        long open      = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.OPEN);
        long won       = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.WON);
        long lost      = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.LOST);
        long returned  = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.RETURNED);
        long cancelled = betRepo.countByTelegramIdAndStatus(telegramId, BetStatus.CANCELLED);
        long total     = open + won + lost + returned + cancelled;

        BigDecimal staked = betRepo.sumTotalStakeByTelegramId(telegramId);
        BigDecimal payout = betRepo.sumActualPayoutByTelegramId(telegramId);
        BigDecimal pl     = payout.subtract(staked);
        double roi = staked.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : pl.divide(staked, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                     .round(new MathContext(4)).doubleValue();

        return new BetStatsDto(total, open, won, lost, returned, cancelled, staked, payout, pl, roi);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<ParticipantRequest> normalizeParticipants(
            List<ParticipantRequest> parts, BigDecimal totalStake) {

        long nullCount = parts.stream()
                .filter(p -> p.profitShare() == null || p.stake() == null)
                .count();

        if (nullCount > 0 && nullCount < parts.size()) {
            throw new IllegalArgumentException(
                    "Необходимо указать доли и ставки либо для всех участников, либо ни для одного");
        }

        if (nullCount == parts.size()) {
            int n = parts.size();
            BigDecimal equalShare = BigDecimal.ONE.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
            BigDecimal equalStake = totalStake.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            List<ParticipantRequest> result = new ArrayList<>(n);
            BigDecimal usedStake = BigDecimal.ZERO;
            BigDecimal usedShare = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                boolean last = (i == n - 1);
                BigDecimal stake = last ? totalStake.subtract(usedStake) : equalStake;
                BigDecimal share = last ? BigDecimal.ONE.subtract(usedShare) : equalShare;
                ParticipantRequest p = parts.get(i);
                result.add(new ParticipantRequest(p.telegramId(), p.displayName(), stake, share, p.bankAccountId()));
                usedStake = usedStake.add(stake);
                usedShare = usedShare.add(share);
            }
            return result;
        }

        BigDecimal shareSum = parts.stream()
                .map(ParticipantRequest::profitShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shareSum.subtract(BigDecimal.ONE).abs().compareTo(SHARE_SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "Сумма долей участников должна быть равна 1.0 (получено: " + shareSum + ")");
        }

        BigDecimal stakeSum = parts.stream()
                .map(ParticipantRequest::stake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stakeSum.subtract(totalStake).abs().compareTo(STAKE_SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "Сумма ставок участников (" + stakeSum + " ₽) не совпадает с общей ставкой ("
                            + totalStake + " ₽)");
        }

        return parts;
    }

    private static BigDecimal computeTotalOdds(List<BetSlipRequest> slips) {
        BigDecimal odds = BigDecimal.ONE;
        for (BetSlipRequest s : slips) {
            odds = odds.multiply(s.odds());
        }
        return odds.setScale(4, RoundingMode.HALF_UP);
    }

    private BetEntity requireAccessible(UUID betId, long telegramId) {
        BetEntity bet = betRepo.findWithDetailById(betId)
                .orElseThrow(() -> new NoSuchElementException("Ставка не найдена"));
        boolean isOwner       = bet.getTelegramId() == telegramId;
        boolean isParticipant = bet.getParticipants().stream()
                .anyMatch(p -> p.getTelegramId() == telegramId);
        if (!isOwner && !isParticipant) {
            throw new SecurityException("Ставка недоступна этому пользователю");
        }
        return bet;
    }

    private BankAccountEntity resolveAccount(ParticipantRequest p) {
        if (p.bankAccountId() != null) {
            BankAccountEntity acct = bankRepo.findById(p.bankAccountId()).orElse(null);
            if (acct == null) return null;
            if (!acct.getOwnerTelegramId().equals(p.telegramId())) {
                throw new SecurityException("Счёт не принадлежит участнику");
            }
            return acct;
        }
        return bankRepo.findByOwnerTelegramId(p.telegramId()).orElse(null);
    }

    private void adjustBalance(BankAccountEntity acct, BigDecimal delta) {
        acct.setBalance(acct.getBalance().add(delta));
        acct.setUpdatedAt(OffsetDateTime.now());
        bankRepo.save(acct);
    }
}
