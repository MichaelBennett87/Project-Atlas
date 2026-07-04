package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class RedToGreenStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "RED_TO_GREEN_REVERSAL";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 4)) return hold(context, 0.0, "Red-to-green waiting for bars.");
        double firstOpen = bars.get(0).open;
        double latest = TechnicalAnalysis.latestClose(bars);
        double low = UnifiedStrategyUtil.lowestLow(bars, 20, 0);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean crossedGreen = firstOpen > 0 && latest > firstOpen && low < firstOpen;
        boolean bullish = TechnicalAnalysis.bullishBreak(bars) || UnifiedStrategyUtil.risingCloses(bars, 2);
        double recovery = firstOpen <= 0 || low <= 0 ? 0.0 : (latest - low) / firstOpen;
        double confidence = TechnicalAnalysis.clamp((crossedGreen ? 0.34 : 0.0) + (bullish ? 0.18 : 0.0) + Math.min(1.0, recovery / 0.07) * 0.24 + Math.min(1.0, rvol / 4.0) * 0.24);
        if (crossedGreen && bullish && rvol >= 1.2 && confidence >= 0.68) return buy(context, confidence, Math.max(0.035, recovery * 0.7), String.format("Red-to-green reversal: recovery=%.2f%% rvol=%.2f", recovery * 100.0, rvol));
        return hold(context, confidence, String.format("Red-to-green not ready: confidence=%.2f crossed=%s", confidence, crossedGreen));
    }
}
