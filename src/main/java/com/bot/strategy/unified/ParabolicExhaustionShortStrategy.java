package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class ParabolicExhaustionShortStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "PARABOLIC_EXHAUSTION_SHORT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 15)) return hold(context, 0.0, "Parabolic exhaustion waiting for bars.");
        Bar latestBar = UnifiedStrategyUtil.latest(bars);
        double runup = UnifiedStrategyUtil.pctChange(bars, 12);
        double rsi = TechnicalAnalysis.rsi(bars, 14);
        double upperWick = UnifiedStrategyUtil.upperWickPct(latestBar);
        double redRatio = UnifiedStrategyUtil.redVolumeRatio(bars, 5);
        boolean reversal = latestBar.close < latestBar.open && upperWick >= 0.006;
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, runup / 0.20) * 0.28 + Math.min(1.0, Math.max(0.0, rsi - 68.0) / 22.0) * 0.20 + Math.min(1.0, upperWick / 0.035) * 0.22 + (reversal ? 0.18 : 0.0) + Math.min(1.0, redRatio / 2.5) * 0.12);
        if (runup >= 0.07 && rsi >= 68.0 && reversal && confidence >= 0.72) return shortSell(context, confidence, Math.max(0.035, runup * 0.28), String.format("Parabolic exhaustion short: runup=%.2f%% rsi=%.1f upperWick=%.2f%% redRatio=%.2f", runup * 100.0, rsi, upperWick * 100.0, redRatio));
        return hold(context, confidence, String.format("Parabolic exhaustion not ready: confidence=%.2f runup=%.2f%% rsi=%.1f", confidence, runup * 100.0, rsi));
    }
}
