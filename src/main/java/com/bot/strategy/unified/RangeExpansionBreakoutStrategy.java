package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class RangeExpansionBreakoutStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "RANGE_EXPANSION_BREAKOUT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 15)) return hold(context, 0.0, "Range expansion waiting for bars.");
        Bar latestBar = UnifiedStrategyUtil.latest(bars);
        double avgRange = 0.0; for (int i = Math.max(0, bars.size() - 11); i < bars.size() - 1; i++) avgRange += Math.max(0.0, bars.get(i).high - bars.get(i).low); avgRange /= Math.max(1, Math.min(10, bars.size() - 1));
        double latestRange = latestBar.high - latestBar.low;
        double expansion = avgRange <= 0 ? 0.0 : latestRange / avgRange;
        double priorHigh = UnifiedStrategyUtil.highestHigh(bars, 12, 1);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean breakout = latestBar.close > priorHigh && latestBar.close > latestBar.open;
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, expansion / 2.5) * 0.30 + (breakout ? 0.30 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.25 + (latestBar.close > TechnicalAnalysis.vwap(bars, 40) ? 0.15 : 0.0));
        if (breakout && expansion >= 1.5 && rvol >= 1.25 && confidence >= 0.70) return buy(context, confidence, 0.04 + Math.min(0.06, expansion / 80.0), String.format("Range expansion breakout: expansion=%.2f rvol=%.2f", expansion, rvol));
        return hold(context, confidence, String.format("Range expansion not ready: confidence=%.2f expansion=%.2f breakout=%s", confidence, expansion, breakout));
    }
}
