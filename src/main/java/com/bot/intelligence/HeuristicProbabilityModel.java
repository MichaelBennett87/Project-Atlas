package com.bot.intelligence;

import com.bot.intelligence.generated.GeneratedAiStrategyPolicy;

public class HeuristicProbabilityModel implements ProbabilityModel {

    @Override
    public ProbabilityPrediction predict(MarketFeatureSnapshot f) {
        if (f == null || f.barCount < 1 || f.lastPrice <= 0.0) {
            return new ProbabilityPrediction(0.0, 1.0, -100.0, "No usable market features.");
        }

        double score = 0.0;
        StringBuilder reason = new StringBuilder();

        if (f.rvol5 >= 2.0 || f.rvol20 >= 2.0) {
            score += GeneratedAiStrategyPolicy.RVOL_EXPANSION_BONUS;
            reason.append("RVOL expansion; ");
        } else if (f.rvol5 >= 1.25 || f.rvol20 >= 1.25) {
            score += GeneratedAiStrategyPolicy.MODERATE_RVOL_BONUS;
            reason.append("moderate RVOL; ");
        } else if (f.rvol5 <= 0.0 && f.rvol20 <= 0.0 && f.barCount < 6) {
            /*
             * Missing RVOL is common during early watchlist formation. It should
             * reduce conviction, not permanently veto otherwise valid VWAP/breakout
             * setups before enough bars exist to estimate RVOL.
             */
            score += GeneratedAiStrategyPolicy.EARLY_MISSING_RVOL_PENALTY;
            reason.append("RVOL unavailable early; ");
        } else {
            score += GeneratedAiStrategyPolicy.WEAK_RVOL_PENALTY;
            reason.append("weak RVOL; ");
        }

        if (f.bullishBreak) {
            score += GeneratedAiStrategyPolicy.BULLISH_BREAK_BONUS;
            reason.append("bullish break; ");
        }
        if (f.reclaimedVwap || f.vwapDistance > 0.002) {
            score += GeneratedAiStrategyPolicy.VWAP_STRENGTH_BONUS;
            reason.append("VWAP strength; ");
        }
        if (f.failedBreakdown) {
            score += GeneratedAiStrategyPolicy.FAILED_BREAKDOWN_BONUS;
            reason.append("failed breakdown; ");
        }
        if (f.higherLows3 && f.noFreshLow3) {
            score += GeneratedAiStrategyPolicy.STRUCTURE_STABILITY_BONUS;
            reason.append("stabilizing structure; ");
        }

        if (f.return1Bar > 0.003) {
            score += GeneratedAiStrategyPolicy.ONE_BAR_CONTINUATION_BONUS;
            reason.append("short-term continuation; ");
        }
        if (f.return3Bars > 0.008) {
            score += GeneratedAiStrategyPolicy.THREE_BAR_CONTINUATION_BONUS;
            reason.append("multi-bar strength; ");
        }
        if (f.dropFromHigh20 > 0.06 && f.bounceFromLow20 < 0.01) {
            score += GeneratedAiStrategyPolicy.FALLING_KNIFE_PENALTY;
            reason.append("falling knife not bouncing; ");
        }
        if (f.rsi14 > 88.0) {
            score += GeneratedAiStrategyPolicy.OVEREXTENDED_RSI_PENALTY;
            reason.append("overextended RSI; ");
        }
        if (f.atrPercent14 > 0.12) {
            score += GeneratedAiStrategyPolicy.EXCESSIVE_VOLATILITY_PENALTY;
            reason.append("excessive volatility; ");
        }
        if (f.sentimentNet > 0.45 && f.catalystScore > 0.35 && f.freshnessSeconds < 300.0) {
            score += GeneratedAiStrategyPolicy.FRESH_CATALYST_BONUS;
            reason.append("fresh positive catalyst; ");
        }
        if (f.sentimentNet < -0.25) {
            score += GeneratedAiStrategyPolicy.NEGATIVE_SENTIMENT_PENALTY;
            reason.append("negative sentiment; ");
        }

        double pTarget = clamp(GeneratedAiStrategyPolicy.BASE_TARGET_PROBABILITY + score);
        double pStop = clamp(0.36 - score * 0.55 + Math.max(0.0, f.atrPercent14 - 0.04));
        double avgWin = GeneratedAiStrategyPolicy.AVG_WIN_PERCENT;
        double avgLoss = GeneratedAiStrategyPolicy.AVG_LOSS_PERCENT;
        double expectedValue = pTarget * avgWin - pStop * avgLoss;

        return new ProbabilityPrediction(
                pTarget,
                pStop,
                expectedValue,
                reason.length() == 0 ? "Neutral feature mix." : reason.toString().trim()
        );
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
