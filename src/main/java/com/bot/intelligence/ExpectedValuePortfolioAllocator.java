package com.bot.intelligence;

import com.bot.master.StrategySignal;
import com.bot.model.TradeDirection;

/**
 * Converts signal quality into a bounded order size. It keeps large conviction
 * trades meaningful while avoiding equal-capital allocation to mediocre setups.
 */
public class ExpectedValuePortfolioAllocator {
    private final double minCapitalFraction = envDouble("EV_ALLOCATOR_MIN_CAPITAL_FRACTION", 0.004);
    private final double maxCapitalFraction = envDouble("EV_ALLOCATOR_MAX_CAPITAL_FRACTION", 0.08);
    private final double shortMaxCapitalFraction = envDouble("EV_ALLOCATOR_SHORT_MAX_CAPITAL_FRACTION", 0.12);
    private final double highConvictionBoost = envDouble("EV_ALLOCATOR_HIGH_CONVICTION_BOOST", 1.35);
    private final AdaptiveStrategyPerformanceTracker performanceTracker = new AdaptiveStrategyPerformanceTracker();
    private final ExecutionCostModel executionCostModel = ExecutionCostModel.getInstance();
    private final StrategySelectionGovernor strategySelectionGovernor = StrategySelectionGovernor.getInstance();

    public int approvedQuantity(StrategySignal signal, double equity, double price) {
        if (signal == null || price <= 0.0 || equity <= 0.0) return signal == null ? 0 : Math.max(0, signal.getSuggestedQuantity());
        double quality = Math.max(0.0, Math.min(1.0, signal.getConfidence()));
        double ev = Math.max(0.0, signal.getExpectedMovePercent());
        double priority = Math.max(0.0, signal.priorityScore());
        double fraction = minCapitalFraction + quality * quality * (maxCapitalFraction - minCapitalFraction);
        if (signal.getDirection() == TradeDirection.SHORT_STOCK) {
            fraction = minCapitalFraction + quality * quality * (shortMaxCapitalFraction - minCapitalFraction);
        }
        if (quality >= 0.92 && ev >= 3.0) {
            fraction *= highConvictionBoost;
        }
        if (priority < envDouble("EV_ALLOCATOR_LOW_PRIORITY_THRESHOLD", 0.015)) {
            fraction *= 0.50;
        }
        double strategyMultiplier = performanceTracker.sizingMultiplier(signal.getStrategyName());
        fraction *= strategyMultiplier;
        StrategySelectionGovernor.RegimeSizingReview regimeReview =
                strategySelectionGovernor.regimeSizingReview(signal.getStrategyName());
        if (regimeReview.disabled && envBool("EV_ALLOCATOR_REGIME_POLICY_CAN_BLOCK", true)) {
            return 0;
        }
        double regimeMultiplier = regimeReview.disabled
                ? Math.max(envDouble("EV_ALLOCATOR_DISABLED_REGIME_MIN_MULTIPLIER", 0.10), regimeReview.sizingMultiplier)
                : regimeReview.sizingMultiplier;
        fraction *= regimeMultiplier;
        ExecutionCostModel.CostReview costReview =
                executionCostModel.review(signal.getTicker(), signal.getStrategyName(), signal.getExpectedMovePercent());
        if (!costReview.approved) {
            return 0;
        }
        fraction *= costReview.sizingMultiplier;
        double effectiveMinFraction = minCapitalFraction * Math.min(
                1.0,
                Math.min(strategyMultiplier, Math.min(regimeMultiplier, costReview.sizingMultiplier))
        );
        fraction = Math.max(
                effectiveMinFraction,
                Math.min(signal.getDirection() == TradeDirection.SHORT_STOCK ? shortMaxCapitalFraction : maxCapitalFraction, fraction)
        );
        int byCapital = (int)Math.floor((equity * fraction) / price);
        int requested = Math.max(1, signal.getSuggestedQuantity());
        int cap = Math.max(1, byCapital);
        if (quality >= 0.97) {
            return Math.max(requested, cap);
        }
        return Math.max(1, Math.min(requested, cap));
    }

    public String describe(StrategySignal signal) {
        if (signal == null) {
            return "evAllocator no signal";
        }
        StrategySelectionGovernor.RegimeSizingReview regimeReview =
                strategySelectionGovernor.regimeSizingReview(signal.getStrategyName());
        ExecutionCostModel.CostReview costReview =
                executionCostModel.review(signal.getTicker(), signal.getStrategyName(), signal.getExpectedMovePercent());
        return performanceTracker.describe(signal.getStrategyName()) +
                " " + regimeReview.summary() +
                " executionCostMultiplier=" + String.format(java.util.Locale.ROOT, "%.3f", costReview.sizingMultiplier) +
                " executionCostReason=" + costReview.reason;
    }

    private static double envDouble(String key,double fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Double.parseDouble(v.trim());}catch(Exception e){return fallback;}}

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }
}
