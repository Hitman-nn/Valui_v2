package com.valui.betting.service.impl;

import com.valui.betting.dto.*;
import com.valui.betting.repository.*;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BettingServiceImpl implements BettingService {

    private static final BigDecimal SHARE_SUM_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal STAKE_SUM_TOLERANCE = BigDecimal.ONE;

    private final BetRepository              betRepo;
    private final BetAccountRepository       accountRepo;
    private final BetPersonRepository        personRepo;
    private final BetPersonBalanceRepository balanceRepo;

    // ── placeBet ──────────────────────────────────────────────────────────────

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

        BetAccountEntity account = null;
        if (req.accountId() != null) {
            account = accountRepo.findById(req.accountId())
                    .filter(a -> a.getChatId().equals(chatId))
                    .orElse(null);
        }

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
                .account(account)
                .slips(new ArrayList<>())
                .participants(new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .build();

        int order = 0;
        for (BetSlipRequest s : req.slips()) {
            bet.getSlips().add(BetSlipEntity.builder()
                    .bet(bet)
                    .matchTitle(s.matchTitle())
                    .matchUrl(s.matchUrl())
                    .bookmaker(s.bookmaker())
                    .odds(s.odds())
                    .result(SlipResult.OPEN)
                    .sortOrder(order++)
                    .build());
        }

        for (ParticipantRequest p : participants) {
            BetPersonEntity person = p.personId() != null
                    ? personRepo.findById(p.personId()).orElse(null)
                    : null;
            bet.getParticipants().add(BetParticipantEntity.builder()
                    .bet(bet)
                    .person(person)
                    .displayName(p.displayName())
                    .stake(p.stake())
                    .profitShare(p.profitShare())
                    .build());
        }

        return BetDto.from(betRepo.save(bet));
    }

    // ── resolveBet ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BetDto resolveBet(UUID betId, long chatId, BetStatus result) {
        if (result != BetStatus.WON && result != BetStatus.LOST && result != BetStatus.RETURNED) {
            throw new IllegalArgumentException("Недопустимый статус для завершения ставки: " + result);
        }

        BetEntity bet = requireAccessible(betId, chatId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Ставка уже завершена: " + bet.getStatus());
        }

        SlipResult slipResult;
        BigDecimal actualPayout;
        switch (result) {
            case WON -> {
                slipResult = SlipResult.WON;
                actualPayout = bet.getTotalStake().multiply(bet.getTotalOdds()).setScale(2, RoundingMode.HALF_UP);
            }
            case RETURNED -> {
                slipResult = SlipResult.RETURNED;
                actualPayout = bet.getTotalStake(); // void bet: payout = stake so P/L = 0
            }
            case LOST -> {
                slipResult = SlipResult.LOST;
                actualPayout = BigDecimal.ZERO;
            }
            default -> throw new IllegalArgumentException("Unexpected status: " + result);
        }

        OffsetDateTime now = OffsetDateTime.now();
        bet.getSlips().forEach(s -> { s.setResult(slipResult); s.setResolvedAt(now); });
        applyBetResult(bet, result, actualPayout);

        return BetDto.from(betRepo.save(bet));
    }

    // ── resolveSlip (express per-match) ──────────────────────────────────────

    @Override
    @Transactional
    public BetDto resolveSlip(UUID betId, int slipSortOrder, long chatId, SlipResult result) {
        if (result == SlipResult.OPEN || result == SlipResult.VOID) {
            throw new IllegalArgumentException("Недопустимый исход для события");
        }

        BetEntity bet = requireAccessible(betId, chatId);
        if (bet.getType() != BetType.EXPRESS) {
            throw new IllegalStateException("Разметка по событиям доступна только для экспресса");
        }

        BetSlipEntity slip = bet.getSlips().stream()
                .filter(s -> s.getSortOrder() == slipSortOrder)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Событие не найдено"));

        // Normal path: open bet, unresolved slip
        if (bet.getStatus() == BetStatus.OPEN && slip.getResult() == SlipResult.OPEN) {
            slip.setResult(result);
            slip.setResolvedAt(OffsetDateTime.now());
            tryAutoResolve(bet);
            return BetDto.from(betRepo.save(bet));
        }

        // Retrospective path: lost bet, void slip — informational only, no P&L changes
        if (bet.getStatus() == BetStatus.LOST && slip.getResult() == SlipResult.VOID) {
            slip.setResult(result);
            slip.setResolvedAt(OffsetDateTime.now());
            return BetDto.from(betRepo.save(bet));
        }

        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Ставка уже завершена");
        }
        throw new IllegalStateException("Исход события уже выставлен");
    }

    // ── cancelBet ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BetDto cancelBet(UUID betId, long chatId) {
        BetEntity bet = requireAccessible(betId, chatId);
        if (bet.getStatus() != BetStatus.OPEN) {
            throw new IllegalStateException("Отменить можно только открытую ставку");
        }
        bet.setStatus(BetStatus.CANCELLED);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());
        bet.setActualPayout(BigDecimal.ZERO);
        return BetDto.from(betRepo.save(bet));
    }

    // ── deleteBet ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteBet(UUID betId, long chatId) {
        BetEntity bet = betRepo.findById(betId)
                .orElseThrow(() -> new NoSuchElementException("Ставка не найдена"));
        if (!bet.getChatId().equals(chatId)) {
            throw new SecurityException("Ставка не принадлежит этому чату");
        }
        betRepo.delete(bet);
    }

    // ── queries ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BetDto getBet(UUID betId, long chatId) {
        BetEntity bet = betRepo.findWithDetailById(betId)
                .orElseThrow(() -> new NoSuchElementException("Ставка не найдена"));
        if (!bet.getChatId().equals(chatId)) {
            throw new SecurityException("Ставка недоступна этому чату");
        }
        return BetDto.from(bet);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BetDto> listBets(long chatId, BetStatus statusFilter, Pageable pageable) {
        Page<BetEntity> page = statusFilter != null
                ? betRepo.findPageByChatIdAndStatus(chatId, statusFilter, pageable)
                : betRepo.findPageByChatId(chatId, pageable);
        if (!page.isEmpty()) {
            List<UUID> ids = page.getContent().stream().map(BetEntity::getId).toList();
            betRepo.findWithSlipsByIds(ids);
            betRepo.findWithParticipantsByIds(ids);
        }
        return page.map(BetDto::from);
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BetStatsDto getStats(long chatId) {
        List<BetRepository.BetStatRow> rows = betRepo.aggregateStatsByChatId(chatId);
        Map<BetStatus, BetRepository.BetStatRow> byStatus = new EnumMap<>(BetStatus.class);
        for (BetRepository.BetStatRow row : rows) byStatus.put(row.getStatus(), row);

        long open      = countFrom(byStatus, BetStatus.OPEN);
        long won       = countFrom(byStatus, BetStatus.WON);
        long lost      = countFrom(byStatus, BetStatus.LOST);
        long returned  = countFrom(byStatus, BetStatus.RETURNED);
        long cancelled = countFrom(byStatus, BetStatus.CANCELLED);
        long total     = open + won + lost + returned + cancelled;

        BigDecimal staked = rows.stream().map(BetRepository.BetStatRow::getTotalStake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payout = rows.stream().map(BetRepository.BetStatRow::getTotalPayout)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pl     = payout.subtract(staked);
        double roi = staked.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : pl.divide(staked, 4, RoundingMode.HALF_UP)
                     .multiply(BigDecimal.valueOf(100))
                     .round(new MathContext(4)).doubleValue();

        return new BetStatsDto(total, open, won, lost, returned, cancelled, staked, payout, pl, roi);
    }

    private static long countFrom(Map<BetStatus, BetRepository.BetStatRow> map, BetStatus status) {
        BetRepository.BetStatRow row = map.get(status);
        return row == null ? 0L : row.getCnt();
    }

    @Override
    @Transactional(readOnly = true)
    public BetAccountStatsDto getAccountStats(UUID accountId, long chatId) {
        BetAccountEntity account = accountRepo.findById(accountId)
                .filter(a -> a.getChatId().equals(chatId))
                .orElseThrow(() -> new NoSuchElementException("Счёт не найден"));

        long open      = betRepo.countByAccount_IdAndStatus(accountId, BetStatus.OPEN);
        long won       = betRepo.countByAccount_IdAndStatus(accountId, BetStatus.WON);
        long lost      = betRepo.countByAccount_IdAndStatus(accountId, BetStatus.LOST);
        long returned  = betRepo.countByAccount_IdAndStatus(accountId, BetStatus.RETURNED);
        long cancelled = betRepo.countByAccount_IdAndStatus(accountId, BetStatus.CANCELLED);
        long total     = open + won + lost + returned + cancelled;

        BigDecimal volume = betRepo.sumTotalStakeByAccountId(accountId);
        BigDecimal payout = betRepo.sumActualPayoutByAccountId(accountId);
        BigDecimal pl     = payout.subtract(volume);

        List<BetPersonBalanceDto> balances = balanceRepo.findByAccountIdWithPerson(accountId)
                .stream().map(BetPersonBalanceDto::from).toList();

        return new BetAccountStatsDto(accountId, account.getName(), total, open, won, lost, returned, volume, payout, pl, balances);
    }

    @Override
    @Transactional(readOnly = true)
    public BetPersonStatsDto getPersonStats(UUID personId, long chatId) {
        BetPersonEntity person = personRepo.findById(personId)
                .filter(p -> p.getChatId().equals(chatId))
                .orElseThrow(() -> new NoSuchElementException("Участник не найден"));

        long open      = betRepo.countByPersonIdAndChatIdAndStatus(personId, chatId, BetStatus.OPEN);
        long won       = betRepo.countByPersonIdAndChatIdAndStatus(personId, chatId, BetStatus.WON);
        long lost      = betRepo.countByPersonIdAndChatIdAndStatus(personId, chatId, BetStatus.LOST);
        long returned  = betRepo.countByPersonIdAndChatIdAndStatus(personId, chatId, BetStatus.RETURNED);
        long total     = open + won + lost + returned
                + betRepo.countByPersonIdAndChatIdAndStatus(personId, chatId, BetStatus.CANCELLED);

        BigDecimal staked = betRepo.sumStakeByPersonId(personId, chatId);
        BigDecimal payout = betRepo.sumPayoutByPersonId(personId, chatId);
        BigDecimal pl     = payout.subtract(staked);
        double roi = staked.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : pl.divide(staked, 4, RoundingMode.HALF_UP)
                     .multiply(BigDecimal.valueOf(100))
                     .round(new MathContext(4)).doubleValue();

        return new BetPersonStatsDto(personId, person.getDisplayName(),
                total, open, won, lost, returned, staked, payout, pl, roi);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BetPersonBalanceDto> getAccountBalances(UUID accountId, long chatId) {
        accountRepo.findById(accountId)
                .filter(a -> a.getChatId().equals(chatId))
                .orElseThrow(() -> new NoSuchElementException("Счёт не найден"));
        return balanceRepo.findByAccountIdWithPerson(accountId)
                .stream().map(BetPersonBalanceDto::from).toList();
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    private void tryAutoResolve(BetEntity bet) {
        List<BetSlipEntity> slips = bet.getSlips();

        boolean anyLost = slips.stream().anyMatch(s -> s.getResult() == SlipResult.LOST);
        if (anyLost) {
            OffsetDateTime now = OffsetDateTime.now();
            slips.stream()
                    .filter(s -> s.getResult() == SlipResult.OPEN)
                    .forEach(s -> { s.setResult(SlipResult.VOID); s.setResolvedAt(now); });
            applyBetResult(bet, BetStatus.LOST, BigDecimal.ZERO);
            return;
        }

        if (slips.stream().anyMatch(s -> s.getResult() == SlipResult.OPEN)) return;

        if (slips.stream().allMatch(s -> s.getResult() == SlipResult.RETURNED)) {
            applyBetResult(bet, BetStatus.RETURNED, bet.getTotalStake());
            return;
        }

        BigDecimal effectiveOdds = slips.stream()
                .filter(s -> s.getResult() == SlipResult.WON)
                .map(BetSlipEntity::getOdds)
                .reduce(BigDecimal.ONE, BigDecimal::multiply)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal actualPayout = bet.getTotalStake()
                .multiply(effectiveOdds)
                .setScale(2, RoundingMode.HALF_UP);

        applyBetResult(bet, BetStatus.WON, actualPayout);
    }

    /**
     * Sets bet status/timestamps and applies P&L to each participant's balance in the bet's account.
     * WIN:      balance += profitShare × (payout − stake)   [net profit]
     * LOSS:     balance -= profitShare × stake               [net loss]
     * RETURNED: no balance change (voided event)
     */
    private void applyBetResult(BetEntity bet, BetStatus status, BigDecimal actualPayout) {
        bet.setStatus(status);
        bet.setResolvedAt(OffsetDateTime.now());
        bet.setUpdatedAt(OffsetDateTime.now());
        bet.setActualPayout(actualPayout);

        BetAccountEntity account = bet.getAccount();
        if (account == null) return;

        BigDecimal stake = bet.getTotalStake();

        for (BetParticipantEntity p : bet.getParticipants()) {
            if (p.getPerson() == null) continue;

            BigDecimal delta = switch (status) {
                case WON      -> actualPayout.subtract(stake).multiply(p.getProfitShare()).setScale(2, RoundingMode.HALF_UP);
                case LOST     -> stake.multiply(p.getProfitShare()).negate().setScale(2, RoundingMode.HALF_UP);
                case RETURNED, CANCELLED -> BigDecimal.ZERO;
                default       -> BigDecimal.ZERO;
            };

            if (delta.compareTo(BigDecimal.ZERO) == 0) continue;
            updatePersonBalance(account, p.getPerson(), delta);
        }
    }

    private void updatePersonBalance(BetAccountEntity account, BetPersonEntity person, BigDecimal delta) {
        BetPersonBalanceEntity bal = balanceRepo
                .findByAccountIdAndPersonId(account.getId(), person.getId())
                .orElseGet(() -> BetPersonBalanceEntity.builder()
                        .account(account)
                        .person(person)
                        .balance(BigDecimal.ZERO)
                        .updatedAt(OffsetDateTime.now())
                        .build());
        bal.setBalance(bal.getBalance().add(delta));
        bal.setUpdatedAt(OffsetDateTime.now());
        balanceRepo.save(bal);
    }

    private BetEntity requireAccessible(UUID betId, long chatId) {
        BetEntity bet = betRepo.findWithDetailById(betId)
                .orElseThrow(() -> new NoSuchElementException("Ставка не найдена"));
        // chatId — основной идентификатор: в личном чате chatId == telegramId,
        // в групповом — chatId = ID группы, что корректно ограничивает доступ чатом.
        if (!Long.valueOf(chatId).equals(bet.getChatId())) {
            throw new SecurityException("Ставка недоступна этому пользователю");
        }
        return bet;
    }

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
                boolean last  = (i == n - 1);
                BigDecimal stake = last ? totalStake.subtract(usedStake) : equalStake;
                BigDecimal share = last ? BigDecimal.ONE.subtract(usedShare) : equalShare;
                ParticipantRequest p = parts.get(i);
                result.add(new ParticipantRequest(p.personId(), p.displayName(), stake, share));
                usedStake = usedStake.add(stake);
                usedShare = usedShare.add(share);
            }
            return result;
        }

        BigDecimal shareSum = parts.stream().map(ParticipantRequest::profitShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shareSum.subtract(BigDecimal.ONE).abs().compareTo(SHARE_SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException("Сумма долей участников должна быть равна 1.0 (получено: " + shareSum + ")");
        }

        BigDecimal stakeSum = parts.stream().map(ParticipantRequest::stake)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stakeSum.subtract(totalStake).abs().compareTo(STAKE_SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "Сумма ставок участников (" + stakeSum + " ₽) не совпадает с общей ставкой (" + totalStake + " ₽)");
        }

        return parts;
    }

    private static BigDecimal computeTotalOdds(List<BetSlipRequest> slips) {
        BigDecimal odds = BigDecimal.ONE;
        for (BetSlipRequest s : slips) odds = odds.multiply(s.odds());
        return odds.setScale(4, RoundingMode.HALF_UP);
    }
}
