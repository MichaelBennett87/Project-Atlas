package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class RelativeStrengthBreakoutStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "RELATIVE_STRENGTH_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 20)) return hold(context, 0.0, "Relative strength breakout waiting for bars.");
        double change5 = UnifiedStrategyUtil.pctChange(bars, 5);
        double change15 = UnifiedStrategyUtil.pctChange(bars, 15);
        double priorHigh = UnifiedStrategyUtil.highestHigh(bars, 15, 1);
        double latest = TechnicalAnalysis.latestClose(bars);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean breakout = latest > priorHigh && priorHigh > 0;
        boolean strength = change5 > 0.012 && change15 > 0.025;
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, change15 / 0.10) * 0.28 + (breakout ? 0.26 : 0.0) + (strength ? 0.18 : 0.0) + Math.min(1.0, rvol / 3.0) * 0.20 + (latest > TechnicalAnalysis.vwap(bars, 40) ? 0.08 : 0.0));
        if (strength && breakout && rvol >= 1.1 && confidence >= 0.69) return buy(context, confidence, Math.max(0.035, change15 * 0.5), String.format("Relative-strength breakout: chg5=%.2f%% chg15=%.2f%% rvol=%.2f", change5 * 100.0, change15 * 100.0, rvol));
        return hold(context, confidence, String.format("Relative-strength breakout not ready: confidence=%.2f strength=%s breakout=%s", confidence, strength, breakout));
    }
}
