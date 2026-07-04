package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class OpeningRangeBreakoutStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "OPENING_RANGE_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 8)) return hold(context, 0.0, "Opening range waiting for bars.");
        double latest = TechnicalAnalysis.latestClose(bars);
        double priorHigh = UnifiedStrategyUtil.highestHigh(bars, 8, 1);
        double range = UnifiedStrategyUtil.consolidationRangePct(bars, 8);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean breakHigh = latest > priorHigh && priorHigh > 0;
        boolean green = UnifiedStrategyUtil.latest(bars).close > UnifiedStrategyUtil.latest(bars).open;
        double confidence = TechnicalAnalysis.clamp((breakHigh ? 0.34 : 0.0) + (green ? 0.12 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.28 + Math.max(0.0, 0.055 - range) / 0.055 * 0.16 + (TechnicalAnalysis.bullishBreak(bars) ? 0.10 : 0.0));
        if (breakHigh && green && rvol >= 1.4 && confidence >= 0.68) return buy(context, confidence, 0.035 + Math.min(0.06, rvol / 140.0), String.format("Opening/range breakout: priorHigh=%.4f latest=%.4f rvol=%.2f range=%.2f%%", priorHigh, latest, rvol, range * 100.0));
        return hold(context, confidence, String.format("Opening/range breakout not ready: confidence=%.2f breakHigh=%s rvol=%.2f", confidence, breakHigh, rvol));
    }
}
