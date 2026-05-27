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
    public AnalyticsResponse getAnalytics(Scope scope, UUID id, Long chatId,
                                          OffsetDateTime from, OffsetDateTime to) {
        List<BetEntity> bets;
        UUID personId = null;

        if (scope == Scope.ACCOUNT) {
            bets = betRepository.findWithSlipsByAccountIdBetween(id, from, to);
        } else {
            personId = id;
            // Two-query pattern to avoid MultipleBagFetchException
            betRepository.findWithParticipantsByPersonIdBetween(id, chatId, from, to);
            bets = betRepository.findWithSlipsByPersonIdBetween(id, chatId, from, to);
        }

        List<BalancePoint>  balance = computeBalanceDynamics(bets, personId);
        List<BetPlPoint>    pl      = computePlPoints(bets, personId);
        DistributionData    dist    = computeDistribution(bets, personId);
        AnalyticsResponse.Summary summary = computeSummary(bets, balance, personId);

        return new AnalyticsResponse(balance, pl, dist, summary);
    }

    // ── Balance dynamics ──────────────────────────────────────────────────────

    private List<BalancePoint> computeBalanceDynamics(List<BetEntity> bets, UUID personId) {
        List<BetEntity> resolved = bets.stream()
                .filter(b -> b.getResolvedAt() != null
                        && b.getStatus() != BetStatus.OPEN
                        && b.getStatus() != BetStatus.CANCELLED)
                .sorted(Comparator.comparing(BetEntity::getResolvedAt))
                .toList();

        // Group by day, accumulate P&L
        Map<java.time.LocalDate, BigDecimal> dailyPnl = new LinkedHashMap<>();
        for (BetEntity b : resolved) {
            java.time.LocalDate day = b.getResolvedAt().toLocalDate();
            BigDecimal pnl = betPnl(b, personId);
            dailyPnl.merge(day, pnl, BigDecimal::add);
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

    private List<BetPlPoint> computePlPoints(List<BetEntity> bets, UUID personId) {
        return bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED)
                .sorted(Comparator.comparing(BetEntity::getCreatedAt))
                .map(b -> new BetPlPoint(
                        b.getId(),
                        b.getCreatedAt().toLocalDate(),
                        betTitle(b),
                        participantStake(b, personId),
                        b.getStatus() == BetStatus.OPEN ? BigDecimal.ZERO : betPnl(b, personId),
                        b.getTotalOdds(),
                        b.getStatus().name()
                ))
                .toList();
    }

    // ── Distribution ──────────────────────────────────────────────────────────

    private DistributionData computeDistribution(List<BetEntity> bets, UUID personId) {
        List<BetEntity> active = bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED)
                .toList();

        // By outcome
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        for (BetStatus s : List.of(BetStatus.WON, BetStatus.LOST, BetStatus.RETURNED, BetStatus.OPEN)) {
            long cnt = active.stream().filter(b -> b.getStatus() == s).count();
            if (cnt > 0) byOutcome.put(s.name(), cnt);
        }

        // By stake (buckets)
        List<DistributionData.BucketEntry> byStake = stakeDistribution(active, personId);

        // By odds (buckets)
        List<DistributionData.BucketEntry> byOdds = oddsDistribution(active);

        return new DistributionData(byOutcome, byStake, byOdds);
    }

    private List<DistributionData.BucketEntry> stakeDistribution(List<BetEntity> bets, UUID personId) {
        long[] buckets = new long[5];
        for (BetEntity b : bets) {
            BigDecimal stake = participantStake(b, personId);
            double s = stake.doubleValue();
            if      (s < 100)   buckets[0]++;
            else if (s < 500)   buckets[1]++;
            else if (s < 1000)  buckets[2]++;
            else if (s < 5000)  buckets[3]++;
            else                buckets[4]++;
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
            if      (o < 1.5)  buckets[0]++;
            else if (o < 2.0)  buckets[1]++;
            else if (o < 3.0)  buckets[2]++;
            else               buckets[3]++;
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
                                                      UUID personId) {
        List<BetEntity> active = bets.stream()
                .filter(b -> b.getStatus() != BetStatus.CANCELLED).toList();

        long total   = active.size();
        long open    = active.stream().filter(b -> b.getStatus() == BetStatus.OPEN).count();
        long won     = active.stream().filter(b -> b.getStatus() == BetStatus.WON).count();
        long lost    = active.stream().filter(b -> b.getStatus() == BetStatus.LOST).count();
        long returned = active.stream().filter(b -> b.getStatus() == BetStatus.RETURNED).count();

        BigDecimal staked = active.stream()
                .map(b -> participantStake(b, personId))
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

    private BigDecimal betPnl(BetEntity b, UUID personId) {
        if (b.getStatus() == BetStatus.OPEN || b.getActualPayout() == null) return BigDecimal.ZERO;
        if (personId == null) {
            // account scope
            return b.getActualPayout().subtract(b.getTotalStake());
        }
        // person scope
        BetParticipantEntity part = findParticipant(b, personId);
        if (part == null) return BigDecimal.ZERO;
        BigDecimal personPayout = part.getProfitShare().multiply(b.getActualPayout());
        return personPayout.subtract(part.getStake());
    }

    private BigDecimal participantStake(BetEntity b, UUID personId) {
        if (personId == null) return b.getTotalStake();
        BetParticipantEntity part = findParticipant(b, personId);
        return part != null ? part.getStake() : BigDecimal.ZERO;
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
            if (b.getSlips().size() > 1) return first + " +" + (b.getSlips().size() - 1);
            return first;
        }
        return b.getType().name();
    }
}
