package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class FailedVwapShortStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "FAILED_VWAP_SHORT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 12)) return hold(context, 0.0, "Failed VWAP short waiting for bars.");
        double latest = TechnicalAnalysis.latestClose(bars); double vwap = TechnicalAnalysis.vwap(bars, 40);
        Bar latestBar = UnifiedStrategyUtil.latest(bars); Bar prev = UnifiedStrategyUtil.previous(bars);
        boolean rejected = vwap > 0 && prev.high >= vwap && latest < vwap && latestBar.close < latestBar.open;
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double redRatio = UnifiedStrategyUtil.redVolumeRatio(bars, 6);
        double confidence = TechnicalAnalysis.clamp((rejected ? 0.34 : 0.0) + Math.min(1.0, rvol / 3.5) * 0.24 + Math.min(1.0, redRatio / 2.5) * 0.20 + (UnifiedStrategyUtil.lowerHighs(bars, 3) ? 0.14 : 0.0) + (UnifiedStrategyUtil.bearishBreak(bars) ? 0.08 : 0.0));
        if (rejected && rvol >= 1.1 && redRatio >= 1.0 && confidence >= 0.70) return shortSell(context, confidence, 0.035 + Math.min(0.05, rvol / 120.0), String.format("Failed VWAP short: latest=%.4f vwap=%.4f rvol=%.2f redRatio=%.2f", latest, vwap, rvol, redRatio));
        return hold(context, confidence, String.format("Failed VWAP short not ready: confidence=%.2f rejected=%s", confidence, rejected));
    }
}
