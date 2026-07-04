package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class OpeningRangeBreakdownShortStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "OPENING_RANGE_BREAKDOWN_SHORT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 8)) return hold(context, 0.0, "Opening breakdown waiting for bars.");
        double latest = TechnicalAnalysis.latestClose(bars);
        double priorLow = UnifiedStrategyUtil.lowestLow(bars, 8, 1);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean breakLow = latest < priorLow && priorLow > 0;
        boolean red = UnifiedStrategyUtil.latest(bars).close < UnifiedStrategyUtil.latest(bars).open;
        double redRatio = UnifiedStrategyUtil.redVolumeRatio(bars, 8);
        double confidence = TechnicalAnalysis.clamp((breakLow ? 0.35 : 0.0) + (red ? 0.12 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.25 + Math.min(1.0, redRatio / 2.5) * 0.18 + (UnifiedStrategyUtil.fallingCloses(bars, 3) ? 0.10 : 0.0));
        if (breakLow && red && rvol >= 1.35 && redRatio >= 1.1 && confidence >= 0.70) return shortSell(context, confidence, 0.035 + Math.min(0.07, rvol / 120.0), String.format("Opening/range breakdown short: priorLow=%.4f latest=%.4f rvol=%.2f redRatio=%.2f", priorLow, latest, rvol, redRatio));
        return hold(context, confidence, String.format("Opening breakdown short not ready: confidence=%.2f breakLow=%s rvol=%.2f", confidence, breakLow, rvol));
    }
}
