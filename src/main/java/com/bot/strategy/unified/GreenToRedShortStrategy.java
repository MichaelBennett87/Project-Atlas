package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class GreenToRedShortStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "GREEN_TO_RED_SHORT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 4)) return hold(context, 0.0, "Green-to-red short waiting for bars.");
        double firstOpen = bars.get(0).open;
        double latest = TechnicalAnalysis.latestClose(bars);
        double high = UnifiedStrategyUtil.highestHigh(bars, 20, 0);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean crossedRed = firstOpen > 0 && latest < firstOpen && high > firstOpen;
        boolean bearish = UnifiedStrategyUtil.bearishBreak(bars) || UnifiedStrategyUtil.fallingCloses(bars, 2);
        double rejection = firstOpen <= 0 || high <= 0 ? 0.0 : (high - latest) / firstOpen;
        double confidence = TechnicalAnalysis.clamp((crossedRed ? 0.34 : 0.0) + (bearish ? 0.18 : 0.0) + Math.min(1.0, rejection / 0.07) * 0.24 + Math.min(1.0, rvol / 4.0) * 0.24);
        if (crossedRed && bearish && rvol >= 1.2 && confidence >= 0.70) return shortSell(context, confidence, Math.max(0.035, rejection * 0.65), String.format("Green-to-red short: rejection=%.2f%% rvol=%.2f", rejection * 100.0, rvol));
        return hold(context, confidence, String.format("Green-to-red short not ready: confidence=%.2f crossed=%s", confidence, crossedRed));
    }
}
