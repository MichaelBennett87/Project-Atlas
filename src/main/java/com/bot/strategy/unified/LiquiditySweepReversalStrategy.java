package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class LiquiditySweepReversalStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "LIQUIDITY_SWEEP_REVERSAL";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 10)) return hold(context, 0.0, "Liquidity sweep waiting for bars.");
        Bar latestBar = UnifiedStrategyUtil.latest(bars);
        double priorLow = UnifiedStrategyUtil.lowestLow(bars, 9, 1);
        boolean swept = priorLow > 0 && latestBar.low < priorLow && latestBar.close > priorLow;
        double lowerWick = UnifiedStrategyUtil.lowerWickPct(latestBar);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean green = latestBar.close > latestBar.open;
        double confidence = TechnicalAnalysis.clamp((swept ? 0.36 : 0.0) + Math.min(1.0, lowerWick / 0.035) * 0.24 + Math.min(1.0, rvol / 4.0) * 0.22 + (green ? 0.10 : 0.0) + (TechnicalAnalysis.latestClose(bars) > TechnicalAnalysis.vwap(bars, 40) ? 0.08 : 0.0));
        if (swept && lowerWick >= 0.006 && rvol >= 1.1 && confidence >= 0.68) return buy(context, confidence, 0.035 + Math.min(0.05, lowerWick * 2.0), String.format("Liquidity sweep reversal: priorLow=%.4f lowerWick=%.2f%% rvol=%.2f", priorLow, lowerWick * 100.0, rvol));
        return hold(context, confidence, String.format("Liquidity sweep not ready: confidence=%.2f swept=%s", confidence, swept));
    }
}
