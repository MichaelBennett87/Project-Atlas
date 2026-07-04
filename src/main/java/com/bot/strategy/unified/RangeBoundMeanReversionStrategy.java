package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class RangeBoundMeanReversionStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "RANGE_BOUND_MEAN_REVERSION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 30)) return hold(context, 0.0, "Range mean-reversion waiting for bars.");
        double high = UnifiedStrategyUtil.highestHigh(bars, 30, 0);
        double low = UnifiedStrategyUtil.lowestLow(bars, 30, 0);
        double latest = TechnicalAnalysis.latestClose(bars);
        double range = high <= 0 || low <= 0 ? 0.0 : (high - low) / latest;
        double location = high <= low ? 0.5 : (latest - low) / (high - low);
        double lowerWick = UnifiedStrategyUtil.lowerWickPct(UnifiedStrategyUtil.latest(bars));
        boolean reclaim = UnifiedStrategyUtil.latest(bars).close > UnifiedStrategyUtil.latest(bars).open;
        double confidence = TechnicalAnalysis.clamp(Math.max(0.0, 0.12 - range) / 0.12 * 0.18 + Math.max(0.0, 0.35 - location) / 0.35 * 0.28 + Math.min(1.0, lowerWick / 0.025) * 0.20 + (reclaim ? 0.18 : 0.0) + (TechnicalAnalysis.rsi(bars, 14) < 42 ? 0.16 : 0.0));
        if (range <= 0.14 && location <= 0.35 && reclaim && confidence >= 0.67) return buy(context, confidence, Math.max(0.025, (0.5 - location) * range), String.format("Range-bound mean reversion: location=%.2f range=%.2f%%", location, range * 100.0));
        return hold(context, confidence, String.format("Range mean-reversion not ready: confidence=%.2f location=%.2f", confidence, location));
    }
}
