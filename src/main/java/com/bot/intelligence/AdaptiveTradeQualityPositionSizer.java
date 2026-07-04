package com.bot.intelligence;

import com.bot.master.StrategySignal;
import com.bot.model.TradeDirection;

/**
 * Final position-size governor layered on top of the existing EV allocator.
 * It reduces size in hostile regimes and increases only when confidence,
 * expected move, and persistent stock memory all agree.
 */
public final class AdaptiveTradeQualityPositionSizer {
    private static final AdaptiveTradeQualityPositionSizer INSTANCE = new AdaptiveTradeQualityPositionSizer();
    private final AdaptiveStrategyPerformanceTracker performanceTracker = new AdaptiveStrategyPerformanceTracker();
    private final StrategySelectionGovernor strategySelectionGovernor = StrategySelectionGovernor.getInstance();

    private AdaptiveTradeQualityPositionSizer() {}

    public static AdaptiveTradeQualityPositionSizer getInstance() {
        return INSTANCE;
    }

    public int approveQuantity(StrategySignal signal, int allocatorQuantity, double accountEquity, double price) {
        if (!envBoolean("ADAPTIVE_TRADE_QUALITY_SIZING_ENABLED", true)) {
            return Math.max(0, allocatorQuantity);
        }
        if (signal == null || allocatorQuantity <= 0 || price <= 0.0 || accountEquity <= 0.0) {
            return Math.max(0, allocatorQuantity);
        }

        double multiplier = 1.0;
        StrategySelectionGovernor.RegimeSizingReview learnedRegime =
                strategySelectionGovernor.regimeSizingReview(signal.getStrategyName());
        if (learnedRegime.disabled && envBoolean("ADAPTIVE_TRADE_QUALITY_REGIME_POLICY_CAN_BLOCK", true)) {
            return 0;
        }
        MarketRegimeSnapshot regime = MarketRegimeEngine.getInstance().currentSnapshot();
        switch (regime.getRegime()) {
            case PANIC:
                multiplier *= signal.getDirection() == TradeDirection.SHORT_STOCK ? 0.95 : 0.65;
                break;
            case DOWNTREND:
                multiplier *= signal.getDirection() == TradeDirection.SHORT_STOCK ? 1.05 : 0.75;
                break;
            case HIGH_VOLATILITY:
                multiplier *= 0.80;
                break;
            case STRONG_UPTREND:
            case UPTREND:
                multiplier *= signal.getDirection() == TradeDirection.SHORT_STOCK ? 0.75 : 1.10;
                break;
            case LOW_LIQUIDITY:
                multiplier *= 0.50;
                break;
            default:
                break;
        }

        StockMemoryProfile profile = StockMemoryService.getInstance().profile(signal.getTicker());
        if (profile != null) {
            double memoryScore = profile.getPredictiveScore();
            if (memoryScore >= 0.80 && signal.getConfidence() >= 0.90) {
                multiplier *= 1.15;
            } else if (memoryScore < 0.35) {
                multiplier *= 0.75;
            }
        }

        if (signal.getConfidence() < 0.70) {
            multiplier *= 0.60;
        } else if (signal.getConfidence() >= 0.95 && signal.getExpectedMovePercent() >= 3.0) {
            multiplier *= 1.10;
        }

        double strategyMultiplier = performanceTracker.sizingMultiplier(signal.getStrategyName());
        multiplier *= strategyMultiplier;
        double learnedRegimeMultiplier = learnedRegime.disabled
                ? Math.max(envDouble("ADAPTIVE_TRADE_QUALITY_DISABLED_REGIME_MIN_MULTIPLIER", 0.10), learnedRegime.sizingMultiplier)
                : learnedRegime.sizingMultiplier;

        double maxFraction = signal.getDirection() == TradeDirection.SHORT_STOCK
                ? envDouble("ADAPTIVE_SHORT_MAX_EQUITY_FRACTION", 0.10)
                : envDouble("ADAPTIVE_LONG_MAX_EQUITY_FRACTION", 0.07);
        if (strategyMultiplier < 1.0) {
            maxFraction *= strategyMultiplier;
        }
        if (learnedRegimeMultiplier < 1.0) {
            maxFraction *= learnedRegimeMultiplier;
        }
        int maxByEquity = Math.max(1, (int)Math.floor((accountEquity * maxFraction) / price));
        int adjusted = Math.max(1, (int)Math.floor(allocatorQuantity * multiplier));
        if (learnedRegimeMultiplier < 1.0 && envBoolean("ADAPTIVE_TRADE_QUALITY_REGIME_CAPS_FINAL_SIZE", true)) {
            adjusted = Math.min(adjusted, allocatorQuantity);
        }
        return Math.max(1, Math.min(adjusted, maxByEquity));
    }

    public String describe(StrategySignal signal, int baseQty, int finalQty) {
        MarketRegimeSnapshot regime = MarketRegimeEngine.getInstance().currentSnapshot();
        StrategySelectionGovernor.RegimeSizingReview learnedRegime =
                strategySelectionGovernor.regimeSizingReview(signal == null ? "UNKNOWN" : signal.getStrategyName());
        return "adaptiveSizing baseQty=" + baseQty +
                " finalQty=" + finalQty +
                " regime=" + regime.getRegime() +
                " strategy=" + (signal == null ? "UNKNOWN" : signal.getStrategyName()) +
                " " + learnedRegime.summary() +
                " " + performanceTracker.describe(signal == null ? "UNKNOWN" : signal.getStrategyName());
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim());
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
