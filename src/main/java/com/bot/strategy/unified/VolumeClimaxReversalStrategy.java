package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class VolumeClimaxReversalStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "VOLUME_CLIMAX_REVERSAL";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 15)) return hold(context, 0.0, "Volume climax reversal waiting for bars.");
        Bar latestBar = UnifiedStrategyUtil.latest(bars);
        double drop = TechnicalAnalysis.percentDropFromRecentHigh(bars, 25);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double lowerWick = UnifiedStrategyUtil.lowerWickPct(latestBar);
        boolean reclaim = latestBar.close > latestBar.open && latestBar.close > (latestBar.low + (latestBar.high - latestBar.low) * 0.55);
        boolean noFreshLow = TechnicalAnalysis.noFreshLow(bars, 3);
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, drop / 0.12) * 0.24 + Math.min(1.0, rvol / 6.0) * 0.26 + Math.min(1.0, lowerWick / 0.035) * 0.22 + (reclaim ? 0.18 : 0.0) + (noFreshLow ? 0.10 : 0.0));
        if (drop >= 0.035 && rvol >= 2.0 && lowerWick >= 0.008 && reclaim && confidence >= 0.70) return buy(context, confidence, Math.max(0.035, drop * 0.35), String.format("Volume-climax reversal: drop=%.2f%% rvol=%.2f lowerWick=%.2f%%", drop * 100.0, rvol, lowerWick * 100.0));
        return hold(context, confidence, String.format("Volume-climax reversal not ready: confidence=%.2f drop=%.2f%% rvol=%.2f", confidence, drop * 100.0, rvol));
    }
}
