package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class TrendPullbackDipBuyStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "TREND_PULLBACK_DIP_BUY";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 30)) return hold(context, 0.0, "Trend pullback waiting for bars.");
        double latest = TechnicalAnalysis.latestClose(bars);
        double sma10 = UnifiedStrategyUtil.smaClose(bars, 10);
        double sma30 = UnifiedStrategyUtil.smaClose(bars, 30);
        double drop = TechnicalAnalysis.percentDropFromRecentHigh(bars, 15);
        double rsi = TechnicalAnalysis.rsi(bars, 14);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean uptrend = sma10 > sma30 && latest > sma30;
        boolean bounce = latest > sma10 && UnifiedStrategyUtil.latest(bars).close > UnifiedStrategyUtil.latest(bars).open;
        double confidence = TechnicalAnalysis.clamp((uptrend ? 0.25 : 0.0) + Math.min(1.0, drop / 0.06) * 0.18 + Math.min(1.0, Math.max(0.0, 55.0 - rsi) / 25.0) * 0.16 + (bounce ? 0.22 : 0.0) + Math.min(1.0, rvol / 3.0) * 0.19);
        if (uptrend && drop >= 0.012 && bounce && rsi <= 58 && confidence >= 0.68) return buy(context, confidence, 0.03 + Math.min(0.04, drop), String.format("Trend pullback dip buy: drop=%.2f%% rsi=%.1f rvol=%.2f", drop * 100.0, rsi, rvol));
        return hold(context, confidence, String.format("Trend pullback not ready: confidence=%.2f uptrend=%s drop=%.2f%%", confidence, uptrend, drop * 100.0));
    }
}
