package com.bot.intelligence;

import com.bot.intelligence.generated.GeneratedAiStrategyPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StrategyProposalGenerator {

    public List<StrategyProposal> generate(MarketFeatureSnapshot f) {
        List<StrategyProposal> proposals = new ArrayList<>();
        if (f == null) {
            return proposals;
        }

        double momentum = clamp((f.return3Bars * GeneratedAiStrategyPolicy.MOMENTUM_RETURN3_MULTIPLIER) + (f.rvol5 >= 1.5 ? GeneratedAiStrategyPolicy.MOMENTUM_RVOL_BONUS : 0.0) + (f.bullishBreak ? GeneratedAiStrategyPolicy.MOMENTUM_BREAK_BONUS : 0.0) + (f.vwapDistance > 0 ? GeneratedAiStrategyPolicy.MOMENTUM_VWAP_BONUS : 0.0));
        proposals.add(new StrategyProposal("FEATURE_MOMENTUM", momentum, "Price/volume continuation score."));

        double meanReversion = clamp(f.dropFromHigh20 * GeneratedAiStrategyPolicy.MEAN_REVERSION_DROP_MULTIPLIER + f.bounceFromLow20 * GeneratedAiStrategyPolicy.MEAN_REVERSION_BOUNCE_MULTIPLIER + (f.reclaimedVwap ? GeneratedAiStrategyPolicy.MEAN_REVERSION_VWAP_BONUS : 0.0) + (f.higherLows3 ? GeneratedAiStrategyPolicy.MEAN_REVERSION_HIGHER_LOWS_BONUS : 0.0));
        proposals.add(new StrategyProposal("FEATURE_MEAN_REVERSION", meanReversion, "Panic/recovery structure score."));

        double vwap = clamp((f.reclaimedVwap ? GeneratedAiStrategyPolicy.VWAP_RECLAIM_BASE_BONUS : 0.0) + (f.vwapDistance > 0.003 ? GeneratedAiStrategyPolicy.VWAP_DISTANCE_BONUS : 0.0) + (f.rvol5 > 1.2 ? GeneratedAiStrategyPolicy.VWAP_RVOL_BONUS : 0.0));
        proposals.add(new StrategyProposal("FEATURE_VWAP_RECLAIM", vwap, "VWAP reclaim and hold score."));

        double squeeze = clamp((f.rvol5 >= 2.0 ? GeneratedAiStrategyPolicy.SQUEEZE_RVOL_BONUS : 0.0) + (f.greenVolumeRatio10 >= 2.0 ? GeneratedAiStrategyPolicy.SQUEEZE_GREEN_VOLUME_BONUS : 0.0) + (f.bullishBreak ? GeneratedAiStrategyPolicy.SQUEEZE_BREAK_BONUS : 0.0) + (f.sentimentNet > 0.3 ? GeneratedAiStrategyPolicy.SQUEEZE_SENTIMENT_BONUS : 0.0));
        proposals.add(new StrategyProposal("FEATURE_SQUEEZE", squeeze, "Volume expansion and green pressure score."));


        double failedBreakdown = clamp((f.failedBreakdown ? 0.45 : 0.0) + (f.noFreshLow3 ? 0.15 : 0.0) + (f.higherLows3 ? 0.12 : 0.0) + (f.reclaimedVwap ? 0.08 : 0.0));
        proposals.add(new StrategyProposal("FEATURE_FAILED_BREAKDOWN", failedBreakdown, "Failed breakdown recovery and stabilization score."));

        double negativeNewsShort = negativeNewsShortScore(f);
        proposals.add(new StrategyProposal("FEATURE_NEGATIVE_NEWS_SHORT", negativeNewsShort,
                "Fresh negative catalyst short score; stale news is blocked by freshness seconds."));

        proposals.sort(Comparator.comparingDouble((StrategyProposal p) -> p.rawScore).reversed());
        return proposals;
    }

    private static double negativeNewsShortScore(MarketFeatureSnapshot f) {
        if (f == null) {
            return 0.0;
        }
        long maxFreshSeconds = envLong("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900L);
        if (f.freshnessSeconds > maxFreshSeconds) {
            return 0.0;
        }

        double negativeSentiment = Math.max(0.0, -f.sentimentNet);
        double negativeContinuation = Math.max(0.0, -f.return3Bars * 16.0) + Math.max(0.0, -f.return1Bar * 10.0);
        double rvolPressure = Math.max(f.rvol5, f.rvol20) >= 1.4 ? 0.18 : 0.0;
        double structureBreak = (!f.reclaimedVwap && !f.higherLows3 ? 0.12 : 0.0) + (f.dropFromHigh20 > 0.025 ? 0.10 : 0.0);
        double catalyst = Math.max(0.0, f.catalystScore) * 0.20;

        return clamp(negativeSentiment * 0.62 + negativeContinuation + rvolPressure + structureBreak + catalyst);
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
