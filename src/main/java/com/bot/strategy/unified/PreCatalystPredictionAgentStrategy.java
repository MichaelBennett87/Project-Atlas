package com.bot.strategy.unified;

import com.bot.intelligence.PredictiveOpportunityRanker;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.model.TradeDirection;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

/**
 * Pre-catalyst swing sleeve strategy.
 *
 * This strategy is deliberately separated from the intraday/parabolic/news engines.
 * It searches the scanner universe for tickers that appear to be quietly accumulating
 * ahead of a likely near-term catalyst, buys only near favorable risk levels, and lets
 * PositionManager apply the dedicated pre-catalyst exit plan when the expected pop and
 * first pullback arrive.
 */
public class PreCatalystPredictionAgentStrategy extends AbstractUnifiedStrategy {

    public static final String STRATEGY_NAME = "PRE_CATALYST_PREDICTION_AGENT";

    private final double minConfidence = envDouble("PRE_CATALYST_MIN_CONFIDENCE", 0.78);
    private final double minPredictiveScore = envDouble("PRE_CATALYST_MIN_PREDICTIVE_SCORE", 0.46);
    private final double minRvol = envDouble("PRE_CATALYST_MIN_RVOL", 1.08);
    private final double maxDistanceAboveVwap = envDouble("PRE_CATALYST_MAX_DISTANCE_ABOVE_VWAP", 0.055);
    private final double maxAtrPercent = envDouble("PRE_CATALYST_MAX_ATR_PERCENT", 0.085);
    private final double maxRunup20 = envDouble("PRE_CATALYST_MAX_RUNUP20", 0.18);
    private final double minAccumulationRatio = envDouble("PRE_CATALYST_MIN_ACCUMULATION_RATIO", 1.12);
    private final double sleeveCapFraction = envDouble("PRE_CATALYST_MAX_CAPITAL_FRACTION", 0.10);

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context == null ? null : context.getBars();
        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 20)) {
            return hold(context, 0.0, "Pre-catalyst agent waiting for at least 20 bars.");
        }

        String ticker = context.getTicker();
        Bar latest = UnifiedStrategyUtil.latest(bars);
        Bar previous = UnifiedStrategyUtil.previous(bars);
        if (latest == null || previous == null || latest.close <= 0.0) {
            return hold(context, 0.0, "Pre-catalyst agent cannot evaluate unusable latest bars.");
        }

        double predictiveScore = PredictiveOpportunityRanker.getInstance().score(ticker);
        double vwap = TechnicalAnalysis.vwap(bars, 60);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double atrPercent = TechnicalAnalysis.atrPercent(bars, 14);
        double runup20 = Math.max(0.0, UnifiedStrategyUtil.pctChange(bars, 20));
        double runup8 = Math.max(0.0, UnifiedStrategyUtil.pctChange(bars, 8));
        double pullbackFromHigh = recentPullbackFromHigh(bars, latest.close, 20);
        double bounceFromLow = TechnicalAnalysis.bounceFromRecentLow(bars, 12);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(bars, 8);
        double redRatio = UnifiedStrategyUtil.redVolumeRatio(bars, 8);
        double accumulationRatio = greenRatio / Math.max(0.20, redRatio);
        double distanceAboveVwap = vwap <= 0.0 ? 0.0 : (latest.close - vwap) / vwap;
        boolean nearVwapOrSupport = vwap > 0.0
                && latest.close >= vwap * 0.965
                && distanceAboveVwap <= maxDistanceAboveVwap;
        boolean higherLows = TechnicalAnalysis.higherLows(bars, 2);
        boolean controlledPullback = pullbackFromHigh >= 0.006 && pullbackFromHigh <= 0.060;
        boolean accumulation = accumulationRatio >= minAccumulationRatio || greenRatio >= 1.35;
        boolean notChasing = runup20 <= maxRunup20 && runup8 <= 0.10;
        boolean liquidEnough = latest.volume >= envDouble("PRE_CATALYST_MIN_LATEST_BAR_VOLUME", 750.0);
        boolean volatilityAcceptable = atrPercent <= maxAtrPercent || atrPercent <= 0.0;

        double confidence = confidence(
                predictiveScore,
                rvol,
                accumulationRatio,
                distanceAboveVwap,
                pullbackFromHigh,
                bounceFromLow,
                runup20,
                atrPercent,
                nearVwapOrSupport,
                higherLows,
                controlledPullback,
                accumulation,
                notChasing,
                liquidEnough
        );

        boolean setup = predictiveScore >= minPredictiveScore
                && rvol >= minRvol
                && nearVwapOrSupport
                && controlledPullback
                && accumulation
                && notChasing
                && liquidEnough
                && volatilityAcceptable
                && confidence >= minConfidence;

        if (!setup) {
            return hold(
                    context,
                    confidence,
                    String.format(
                            "Pre-catalyst watch: predictive=%.2f rvol=%.2f accum=%.2f pullback=%.2f%% runup20=%.2f%% distVwap=%.2f%% atr=%.2f%% nearVwap=%s higherLows=%s",
                            predictiveScore,
                            rvol,
                            accumulationRatio,
                            pullbackFromHigh * 100.0,
                            runup20 * 100.0,
                            distanceAboveVwap * 100.0,
                            atrPercent * 100.0,
                            nearVwapOrSupport,
                            higherLows
                    )
            );
        }

        int quantity = cappedSleeveQuantity(context, confidence, latest.close);
        if (quantity <= 0) {
            return hold(context, confidence, "Pre-catalyst setup passed but sleeve sizing returned zero quantity.");
        }

        double expectedMove = Math.max(0.025, Math.min(0.18, predictiveScore * 0.13 + rvol * 0.006));
        return StrategySignal.buy(
                name(),
                context.getTicker(),
                TradeDirection.LONG_STOCK,
                confidence,
                expectedMove,
                quantity,
                String.format(
                        "Pre-catalyst accumulation entry: predictive=%.2f rvol=%.2f accum=%.2f pullback=%.2f%% bounce=%.2f%% runup20=%.2f%% distanceAboveVwap=%.2f%% sleeveCap=%.1f%%",
                        predictiveScore,
                        rvol,
                        accumulationRatio,
                        pullbackFromHigh * 100.0,
                        bounceFromLow * 100.0,
                        runup20 * 100.0,
                        distanceAboveVwap * 100.0,
                        sleeveCapFraction * 100.0
                )
        );
    }

    private double confidence(
            double predictiveScore,
            double rvol,
            double accumulationRatio,
            double distanceAboveVwap,
            double pullbackFromHigh,
            double bounceFromLow,
            double runup20,
            double atrPercent,
            boolean nearVwapOrSupport,
            boolean higherLows,
            boolean controlledPullback,
            boolean accumulation,
            boolean notChasing,
            boolean liquidEnough
    ) {
        double score = 0.0;
        score += Math.min(0.24, predictiveScore * 0.24);
        score += Math.min(0.13, rvol / 4.0 * 0.13);
        score += Math.min(0.13, accumulationRatio / 3.0 * 0.13);
        score += nearVwapOrSupport ? 0.13 : -0.10;
        score += higherLows ? 0.08 : 0.0;
        score += controlledPullback ? 0.12 : -0.08;
        score += Math.min(0.06, bounceFromLow / 0.04 * 0.06);
        score += accumulation ? 0.07 : 0.0;
        score += notChasing ? 0.07 : -0.12;
        score += liquidEnough ? 0.04 : -0.10;
        if (distanceAboveVwap < -0.025) score -= 0.05;
        if (runup20 > maxRunup20) score -= 0.10;
        if (atrPercent > maxAtrPercent && atrPercent > 0) score -= 0.10;
        return TechnicalAnalysis.clamp(score);
    }

    private int cappedSleeveQuantity(StrategyContext context, double confidence, double price) {
        if (context == null || price <= 0.0 || context.getAccountEquity() <= 0.0 || confidence < 0.60) {
            return 0;
        }
        double maxDollars = context.getAccountEquity() * Math.max(0.005, Math.min(0.10, sleeveCapFraction));
        double confidenceScale = Math.max(0.25, Math.min(1.0, (confidence - 0.55) / 0.35));
        double plannedDollars = maxDollars * confidenceScale;
        return Math.max(1, (int)Math.floor(plannedDollars / price));
    }

    private static double recentPullbackFromHigh(List<Bar> bars, double latestClose, int lookback) {
        double high = UnifiedStrategyUtil.highestHigh(bars, lookback, 0);
        if (high <= 0.0 || latestClose <= 0.0 || latestClose >= high) {
            return 0.0;
        }
        return (high - latestClose) / high;
    }
}
