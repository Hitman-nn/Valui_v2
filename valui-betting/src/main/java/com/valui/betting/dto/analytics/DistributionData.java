package com.valui.betting.dto.analytics;

import java.util.List;
import java.util.Map;

public record DistributionData(
        Map<String, Long>  byOutcome,
        List<BucketEntry>  byStake,
        List<BucketEntry>  byOdds
) {
    public record BucketEntry(String label, long count) {}
}
