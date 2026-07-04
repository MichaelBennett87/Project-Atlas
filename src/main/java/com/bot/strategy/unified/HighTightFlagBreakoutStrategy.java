package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class HighTightFlagBreakoutStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "HIGH_TIGHT_FLAG_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 18)) return hold(context, 0.0, "High-tight flag waiting for bars.");
        double runup = UnifiedStrategyUtil.pctChange(bars, 12);
        double flagRange = UnifiedStrategyUtil.consolidationRangePct(bars, 6);
        double latest = TechnicalAnalysis.latestClose(bars);
        double flagHigh = UnifiedStrategyUtil.highestHigh(bars, 6, 1);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean breakout = latest > flagHigh && flagHigh > 0;
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, runup / 0.18) * 0.28 + Math.max(0.0, 0.045 - flagRange) / 0.045 * 0.22 + (breakout ? 0.25 : 0.0) + Math.min(1.0, rvol / 3.5) * 0.18 + (UnifiedStrategyUtil.risingCloses(bars, 2) ? 0.07 : 0.0));
        if (runup >= 0.06 && flagRange <= 0.055 && breakout && rvol >= 1.2 && confidence >= 0.70) return buy(context, confidence, Math.max(0.05, runup * 0.45), String.format("High-tight flag breakout: runup=%.2f%% flagRange=%.2f%% rvol=%.2f", runup * 100.0, flagRange * 100.0, rvol));
        return hold(context, confidence, String.format("High-tight flag not ready: confidence=%.2f runup=%.2f%% flagRange=%.2f%%", confidence, runup * 100.0, flagRange * 100.0));
    }
}
