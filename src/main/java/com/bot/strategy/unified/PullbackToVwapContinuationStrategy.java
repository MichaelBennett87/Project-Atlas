package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class PullbackToVwapContinuationStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "PULLBACK_TO_VWAP_CONTINUATION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 20)) return hold(context, 0.0, "VWAP pullback waiting for bars.");
        double latest = TechnicalAnalysis.latestClose(bars);
        double vwap = TechnicalAnalysis.vwap(bars, 40);
        double runup = UnifiedStrategyUtil.pctChange(bars, 15);
        double distance = vwap <= 0 ? 0.0 : Math.abs(latest - vwap) / vwap;
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean above = latest > vwap && vwap > 0;
        boolean bounce = UnifiedStrategyUtil.latest(bars).close > UnifiedStrategyUtil.latest(bars).open && TechnicalAnalysis.higherLows(bars, 2);
        double confidence = TechnicalAnalysis.clamp(Math.min(1.0, runup / 0.10) * 0.22 + (above ? 0.16 : 0.0) + Math.max(0.0, 0.018 - distance) / 0.018 * 0.22 + (bounce ? 0.20 : 0.0) + Math.min(1.0, rvol / 3.0) * 0.20);
        if (runup >= 0.025 && above && distance <= 0.018 && bounce && rvol >= 1.0 && confidence >= 0.68) return buy(context, confidence, 0.035 + Math.min(0.04, runup * 0.3), String.format("VWAP pullback continuation: runup=%.2f%% distance=%.2f%% rvol=%.2f", runup * 100.0, distance * 100.0, rvol));
        return hold(context, confidence, String.format("VWAP pullback not ready: confidence=%.2f above=%s distance=%.2f%%", confidence, above, distance * 100.0));
    }
}
