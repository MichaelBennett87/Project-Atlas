package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class InsideBarExpansionStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "INSIDE_BAR_EXPANSION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 4)) return hold(context, 0.0, "Inside-bar expansion waiting for bars.");
        Bar latestBar = UnifiedStrategyUtil.latest(bars); Bar prev = UnifiedStrategyUtil.previous(bars); Bar twoBack = bars.get(bars.size() - 3);
        boolean inside = prev.high < twoBack.high && prev.low > twoBack.low;
        boolean expansion = latestBar.close > prev.high && latestBar.close > latestBar.open;
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double body = UnifiedStrategyUtil.candleBodyPct(latestBar);
        double confidence = TechnicalAnalysis.clamp((inside ? 0.25 : 0.0) + (expansion ? 0.30 : 0.0) + Math.min(1.0, rvol / 3.0) * 0.25 + Math.min(1.0, body / 0.025) * 0.20);
        if (inside && expansion && rvol >= 1.2 && confidence >= 0.68) return buy(context, confidence, 0.035 + Math.min(0.05, body * 2.0), String.format("Inside-bar expansion: body=%.2f%% rvol=%.2f", body * 100.0, rvol));
        return hold(context, confidence, String.format("Inside-bar expansion not ready: confidence=%.2f inside=%s expansion=%s", confidence, inside, expansion));
    }
}
