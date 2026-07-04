package com.bot.strategy.unified;

import com.bot.intelligence.ParabolicTopVolumeTracker;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

/**
 * Intraday parabolic momentum strategy for the most active stocks of the day.
 *
 * This strategy is intentionally different from long-horizon LLM portfolio
 * selection. It only cares about short-term, violent intraday movement and can
 * trade both directions:
 *
 *  - Long: controlled pullback in a parabolic mover, then continuation/reclaim.
 *  - Short: exhaustion at/near the peak, then failed high/reversal confirmation.
 *
 * The strategy produces entry signals only. PositionManager remains responsible
 * for the momentum exit/cover plan, hard stops, trailing exits, and broker-safe
 * position cleanup.
 */
public class ParabolicBiDirectionalMomentumAgentStrategy extends AbstractUnifiedStrategy {

    private static final String STRATEGY_NAME = "PARABOLIC_BI_DIRECTIONAL_MOMENTUM_AGENT";

    private final ParabolicTopVolumeTracker topVolumeTracker = ParabolicTopVolumeTracker.getInstance();
    private final int topCount = Math.max(5, envInt("PARABOLIC_TOP_VOLUME_COUNT", 25));
    private final int graceRank = Math.max(topCount, envInt("PARABOLIC_TOP_VOLUME_GRACE_RANK", 40));
    private final double minRunup = envDouble("PARABOLIC_MIN_RUNUP", 0.045);
    private final double minShortRunup = envDouble("PARABOLIC_SHORT_MIN_RUNUP", 0.075);
    private final double minPullback = envDouble("PARABOLIC_MIN_PULLBACK", 0.010);
    private final double maxLongPullback = envDouble("PARABOLIC_MAX_LONG_PULLBACK", 0.085);
    private final double maxShortPullback = envDouble("PARABOLIC_MAX_SHORT_PULLBACK", 0.135);
    private final double minRvol = envDouble("PARABOLIC_MIN_RVOL", 1.20);
    private final double minLongConfidence = envDouble("PARABOLIC_LONG_MIN_CONFIDENCE", 0.74);
    private final double minShortConfidence = envDouble("PARABOLIC_SHORT_MIN_CONFIDENCE", 0.76);

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context == null ? null : context.getBars();
        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 18)) {
            return hold(context, 0.0, "Parabolic bi-directional agent waiting for at least 18 bars.");
        }

        String ticker = context.getTicker();
        if (!topVolumeTracker.isTopVolumeTicker(ticker, graceRank)) {
            return hold(context, 0.0, "Ticker is not in the parabolic top-volume universe. " + topVolumeTracker.describe(ticker));
        }

        Bar latest = UnifiedStrategyUtil.latest(bars);
        Bar previous = UnifiedStrategyUtil.previous(bars);
        if (latest == null || previous == null || latest.close <= 0.0 || previous.close <= 0.0) {
            return hold(context, 0.0, "Latest bars unusable for parabolic decision.");
        }

        double latestClose = latest.close;
        double vwap = TechnicalAnalysis.vwap(bars, 60);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double runup20 = Math.max(UnifiedStrategyUtil.pctChange(bars, 20), UnifiedStrategyUtil.pctChange(bars, 12));
        double runup8 = UnifiedStrategyUtil.pctChange(bars, 8);
        double high20 = UnifiedStrategyUtil.highestHigh(bars, 20, 0);
        double low8 = UnifiedStrategyUtil.lowestLow(bars, 8, 0);
        double pullbackFromHigh = high20 <= 0.0 ? 0.0 : Math.max(0.0, (high20 - latestClose) / high20);
        double bounceFromLow = low8 <= 0.0 ? 0.0 : Math.max(0.0, (latestClose - low8) / low8);
        double upperWick = UnifiedStrategyUtil.upperWickPct(latest);
        double lowerWick = UnifiedStrategyUtil.lowerWickPct(latest);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(bars, 6);
        double redRatio = UnifiedStrategyUtil.redVolumeRatio(bars, 6);
        double atrPercent = TechnicalAnalysis.atrPercent(bars, 14);
        boolean aboveVwap = vwap > 0.0 && latestClose > vwap;
        boolean reclaimedVwap = TechnicalAnalysis.reclaimedVwap(bars, 40);
        boolean lostVwap = vwap > 0.0 && latestClose < vwap && previous.close >= vwap;
        boolean higherLows = TechnicalAnalysis.higherLows(bars, 2);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(bars);
        boolean bearishBreak = UnifiedStrategyUtil.bearishBreak(bars);
        boolean lowerHighs = UnifiedStrategyUtil.lowerHighs(bars, 2);
        boolean greenCandle = latest.close > latest.open;
        boolean redCandle = latest.close < latest.open;
        boolean top25 = topVolumeTracker.isTopVolumeTicker(ticker, topCount);

        double longConfidence = longContinuationConfidence(
                top25,
                runup20,
                runup8,
                pullbackFromHigh,
                bounceFromLow,
                rvol,
                aboveVwap,
                reclaimedVwap,
                higherLows,
                bullishBreak,
                greenCandle,
                greenRatio,
                lowerWick,
                atrPercent
        );

        double shortConfidence = shortExhaustionConfidence(
                top25,
                runup20,
                pullbackFromHigh,
                rvol,
                lostVwap,
                bearishBreak,
                lowerHighs,
                redCandle,
                redRatio,
                upperWick,
                atrPercent
        );

        boolean longSetup = runup20 >= minRunup
                && pullbackFromHigh >= minPullback
                && pullbackFromHigh <= maxLongPullback
                && rvol >= minRvol
                && (aboveVwap || reclaimedVwap)
                && (higherLows || bullishBreak || bounceFromLow >= 0.010)
                && greenCandle
                && longConfidence >= minLongConfidence;

        boolean shortSetup = runup20 >= minShortRunup
                && pullbackFromHigh >= minPullback
                && pullbackFromHigh <= maxShortPullback
                && rvol >= minRvol
                && (bearishBreak || lowerHighs || lostVwap || upperWick >= 0.008)
                && redCandle
                && shortConfidence >= minShortConfidence;

        if (longSetup && longConfidence >= shortConfidence) {
            return buy(
                    context,
                    longConfidence,
                    Math.max(0.030, Math.min(0.12, runup20 * 0.35 + bounceFromLow * 0.50)),
                    String.format(
                            "Parabolic continuation long: %s runup=%.2f%% pullback=%.2f%% bounce=%.2f%% rvol=%.2f vwap=%s reclaimed=%s greenRatio=%.2f",
                            topVolumeTracker.describe(ticker),
                            runup20 * 100.0,
                            pullbackFromHigh * 100.0,
                            bounceFromLow * 100.0,
                            rvol,
                            aboveVwap,
                            reclaimedVwap,
                            greenRatio
                    )
            );
        }

        if (shortSetup) {
            return shortSell(
                    context,
                    shortConfidence,
                    Math.max(0.025, Math.min(0.10, pullbackFromHigh * 0.65 + upperWick * 0.45)),
                    String.format(
                            "Parabolic peak exhaustion short: %s runup=%.2f%% pullback=%.2f%% rvol=%.2f upperWick=%.2f%% redRatio=%.2f lostVwap=%s bearishBreak=%s lowerHighs=%s",
                            topVolumeTracker.describe(ticker),
                            runup20 * 100.0,
                            pullbackFromHigh * 100.0,
                            rvol,
                            upperWick * 100.0,
                            redRatio,
                            lostVwap,
                            bearishBreak,
                            lowerHighs
                    )
            );
        }

        double confidence = Math.max(longConfidence, shortConfidence);
        return hold(
                context,
                confidence,
                String.format(
                        "Parabolic agent watching: %s runup=%.2f%% pullback=%.2f%% rvol=%.2f longC=%.2f shortC=%.2f aboveVwap=%s bearishBreak=%s",
                        topVolumeTracker.describe(ticker),
                        runup20 * 100.0,
                        pullbackFromHigh * 100.0,
                        rvol,
                        longConfidence,
                        shortConfidence,
                        aboveVwap,
                        bearishBreak
                )
        );
    }

    private double longContinuationConfidence(
            boolean top25,
            double runup20,
            double runup8,
            double pullbackFromHigh,
            double bounceFromLow,
            double rvol,
            boolean aboveVwap,
            boolean reclaimedVwap,
            boolean higherLows,
            boolean bullishBreak,
            boolean greenCandle,
            double greenRatio,
            double lowerWick,
            double atrPercent
    ) {
        double score = 0.0;
        score += top25 ? 0.12 : 0.07;
        score += Math.min(0.18, Math.max(0.0, runup20) / 0.18 * 0.18);
        score += Math.min(0.10, Math.max(0.0, runup8) / 0.08 * 0.10);
        score += pullbackFromHigh >= minPullback && pullbackFromHigh <= maxLongPullback ? 0.13 : -0.12;
        score += Math.min(0.10, bounceFromLow / 0.035 * 0.10);
        score += Math.min(0.12, rvol / 5.0 * 0.12);
        score += aboveVwap ? 0.09 : 0.0;
        score += reclaimedVwap ? 0.10 : 0.0;
        score += higherLows ? 0.08 : 0.0;
        score += bullishBreak ? 0.10 : 0.0;
        score += greenCandle ? 0.05 : -0.05;
        score += Math.min(0.06, greenRatio / 3.0 * 0.06);
        score += Math.min(0.04, lowerWick / 0.025 * 0.04);
        if (atrPercent > 0.075) score -= 0.08;
        return TechnicalAnalysis.clamp(score);
    }

    private double shortExhaustionConfidence(
            boolean top25,
            double runup20,
            double pullbackFromHigh,
            double rvol,
            boolean lostVwap,
            boolean bearishBreak,
            boolean lowerHighs,
            boolean redCandle,
            double redRatio,
            double upperWick,
            double atrPercent
    ) {
        double score = 0.0;
        score += top25 ? 0.13 : 0.08;
        score += Math.min(0.20, Math.max(0.0, runup20) / 0.25 * 0.20);
        score += pullbackFromHigh >= minPullback && pullbackFromHigh <= maxShortPullback ? 0.13 : -0.10;
        score += Math.min(0.12, rvol / 5.0 * 0.12);
        score += lostVwap ? 0.12 : 0.0;
        score += bearishBreak ? 0.13 : 0.0;
        score += lowerHighs ? 0.08 : 0.0;
        score += redCandle ? 0.07 : -0.05;
        score += Math.min(0.08, redRatio / 3.0 * 0.08);
        score += Math.min(0.10, upperWick / 0.035 * 0.10);
        if (atrPercent > 0.095) score -= 0.06;
        return TechnicalAnalysis.clamp(score);
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
