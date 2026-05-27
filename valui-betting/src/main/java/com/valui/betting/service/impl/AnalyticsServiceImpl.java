package com.valui.betting.service.impl;

import com.valui.betting.dto.analytics.*;
import com.valui.betting.repository.BetRepository;
import com.valui.betting.service.AnalyticsService;
import com.valui.common.domain.BetStatus;
import com.valui.common.entity.BetEntity;
import com.valui.common.entity.BetParticipantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final BetRepository betRepository;

    @Override
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(List<UUID> accountIds, Long telegramId,
                                          List<UUID> personIds, OffsetDateTime from, OffsetDateTime to) {
        List<BetEntity> bets;
        List<UUID> effectivePersonIds = null;

        boolean hasPersonFilter = personIds != null && !personIds.isEmpty();

        if (hasPersonFilter) {
            // Admin: bets on selected accounts where selected persons participated
            effectivePersonIds = personIds;
            betRepository.findWithParticipantsByAccountIdsAndPersonIdsAndBetween(accountIds, personIds, from, to);
            bets = betRepository.findWithSlipsByAccountIdsAndPersonIdsAndBetween(accountIds, personIds, from, to);
        } else if (telegramId != null) {
            // Regular user: only their own bets on selected accounts
            bets = betRepository.findWithSlipsByAccountIdsAndTelegramIdBetween(accountIds, telegramId, from, to);
        } else {
            // Admin without person filter: all bets on selected accounts
            bets = betRepository.findWithSlipsByAccountIdsBetween(accountIds, from, to);
        }

        List<BalancePoint>        balance = computeBalanceDynamics(bets, effectivePersonIds);
        List<BetPlPoint>          pl      = computePlPoints(bets, effectivePersonIds);
        DistributionData          dist    = computeDistribution(bets, effectivePersonIds);
        AnalyticsResponse.Summary summary = computeSummary(bets, balance, effectivePersonIds);

        return new AnalyticsResponse(balance, pl, dist, summary);
    }

    // ── Balance dynamics ──────────────────────────────────────────────────────

    private List<BalancePoint> computeBalanceDynamics(List<BetEntity> bets, List<UUID> personIds) {
        List<BetEntity> resolved = bets.stream()
                .filter(b -> b.getResolvedAt() != null
                        && b.getStatus() != BetStatus.OPEN
                        && b.getStatus() != BetStatus.CANCELLED)
                .sorted(Comparator.comparing(BetEntity::getResolvedAt))
                .toList();

        Map<java.time.LocalDate, BigDecimal> dailyPnl = new LinkedHashMap<>();
        for (BetEntity b : resolved) {
            dailyPnl.merge(b.getResolvedAt().toLocalDate(), betPnl(b, personIds), BigDecimal::add);
        }

        List<BalancePoint> points = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (var entry : dailyPnl.entrySet()) {
            running = running.add(entry.getValue());
            points.add(new BalancePoint(entry.getKey(), entry.getValue(), running));
        }
        return points;
    }

    // ── P&L per bet ───────────────────────────────────────────────────────────

    private List<BetPlPoint> computePlPoints(List<BetEntity> bets, List<UUID> personIds) {
        return bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED)
                .sorted(Comparator.comparing(BetEntity::getCreatedAt))
                .map(b -> new BetPlPoint(
                        b.getId(),
                        b.getCreatedAt().toLocalDate(),
                        betTitle(b),
                        participantStake(b, personIds),
                        b.getStatus() == BetStatus.OPEN ? BigDecimal.ZERO : betPnl(b, personIds),
                        b.getTotalOdds(),
                        b.getStatus().name()
                ))
                .toList();
    }

    // ── Distribution ──────────────────────────────────────────────────────────

    private DistributionData computeDistribution(List<BetEntity> bets, List<UUID> personIds) {
        List<BetEntity> active = bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED).toList();

        Map<String, Long> byOutcome = new LinkedHashMap<>();
        for (BetStatus s : List.of(BetStatus.WON, BetStatus.LOST, BetStatus.RETURNED, BetStatus.OPEN)) {
            long cnt = active.stream().filter(b -> b.getStatus() == s).count();
            if (cnt > 0) byOutcome.put(s.name(), cnt);
        }

        return new DistributionData(byOutcome, stakeDistribution(active, personIds), oddsDistribution(active));
    }

    private List<DistributionData.BucketEntry> stakeDistribution(List<BetEntity> bets, List<UUID> personIds) {
        long[] buckets = new long[5];
        for (BetEntity b : bets) {
            double s = participantStake(b, personIds).doubleValue();
            if      (s < 100)  buckets[0]++;
            else if (s < 500)  buckets[1]++;
            else if (s < 1000) buckets[2]++;
            else if (s < 5000) buckets[3]++;
            else               buckets[4]++;
        }
        String[] labels = {"<100", "100–500", "500–1000", "1000–5000", "5000+"};
        List<DistributionData.BucketEntry> result = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (buckets[i] > 0) result.add(new DistributionData.BucketEntry(labels[i], buckets[i]));
        }
        return result;
    }

    private List<DistributionData.BucketEntry> oddsDistribution(List<BetEntity> bets) {
        long[] buckets = new long[4];
        for (BetEntity b : bets) {
            double o = b.getTotalOdds().doubleValue();
            if      (o < 1.5) buckets[0]++;
            else if (o < 2.0) buckets[1]++;
            else if (o < 3.0) buckets[2]++;
            else              buckets[3]++;
        }
        String[] labels = {"1.0–1.5", "1.5–2.0", "2.0–3.0", "3.0+"};
        List<DistributionData.BucketEntry> result = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (buckets[i] > 0) result.add(new DistributionData.BucketEntry(labels[i], buckets[i]));
        }
        return result;
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private AnalyticsResponse.Summary computeSummary(List<BetEntity> bets,
                                                      List<BalancePoint> balance,
                                                      List<UUID> personIds) {
        List<BetEntity> active = bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED).toList();

        long total    = active.size();
        long open     = active.stream().filter(b -> b.getStatus() == BetStatus.OPEN).count();
        long won      = active.stream().filter(b -> b.getStatus() == BetStatus.WON).count();
        long lost     = active.stream().filter(b -> b.getStatus() == BetStatus.LOST).count();
        long returned = active.stream().filter(b -> b.getStatus() == BetStatus.RETURNED).count();

        BigDecimal staked = active.stream()
                .filter(b -> b.getStatus() != BetStatus.OPEN)
                .map(b -> participantStake(b, personIds))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnl = balance.isEmpty() ? BigDecimal.ZERO
                : balance.get(balance.size() - 1).cumulative();

        double roi = staked.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : totalPnl.divide(staked, 4, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100))
                          .doubleValue();

        return new AnalyticsResponse.Summary(total, open, won, lost, returned, staked, totalPnl, roi);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal betPnl(BetEntity b, List<UUID> personIds) {
        if (b.getStatus() == BetStatus.OPEN || b.getActualPayout() == null) return BigDecimal.ZERO;
        if (personIds == null) {
            return b.getActualPayout().subtract(b.getTotalStake());
        }
        BigDecimal pnl = BigDecimal.ZERO;
        for (UUID pid : personIds) {
            BetParticipantEntity p = findParticipant(b, pid);
            if (p != null) {
                pnl = pnl.add(p.getProfitShare().multiply(b.getActualPayout()).subtract(p.getStake()));
            }
        }
        return pnl;
    }

    private BigDecimal participantStake(BetEntity b, List<UUID> personIds) {
        if (personIds == null) return b.getTotalStake();
        BigDecimal total = BigDecimal.ZERO;
        for (UUID pid : personIds) {
            BetParticipantEntity p = findParticipant(b, pid);
            if (p != null) total = total.add(p.getStake());
        }
        return total;
    }

    private BetParticipantEntity findParticipant(BetEntity b, UUID personId) {
        if (b.getParticipants() == null) return null;
        return b.getParticipants().stream()
                .filter(p -> p.getPerson() != null && personId.equals(p.getPerson().getId()))
                .findFirst().orElse(null);
    }

    private String betTitle(BetEntity b) {
        if (b.getSlips() != null && !b.getSlips().isEmpty()) {
            String first = b.getSlips().get(0).getMatchTitle();
            return b.getSlips().size() > 1 ? first + " +" + (b.getSlips().size() - 1) : first;
        }
        return b.getType().name();
    }
}
