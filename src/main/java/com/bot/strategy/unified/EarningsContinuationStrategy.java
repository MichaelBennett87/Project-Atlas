package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class EarningsContinuationStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "EARNINGS_CONTINUATION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!context.hasNews()) return hold(context, 0.0, "No earnings catalyst.");
        String text = UnifiedStrategyUtil.lowerNews(context.newsText());
        boolean earnings = text.contains("eps") || text.contains("earnings") || text.contains("quarter") || text.contains("record results");
        boolean beat = text.contains("beats") || text.contains("beat estimate") || text.contains("raises guidance") || text.contains("record results");
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean bullish = TechnicalAnalysis.bullishBreak(bars) || UnifiedStrategyUtil.risingCloses(bars, 2);
        double confidence = TechnicalAnalysis.clamp((earnings ? 0.18 : 0.0) + (beat ? 0.24 : 0.0) + (bullish ? 0.22 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.24 + (TechnicalAnalysis.latestClose(bars) > TechnicalAnalysis.vwap(bars, 40) ? 0.12 : 0.0));
        if (earnings && beat && bullish && rvol >= 1.25 && confidence >= 0.70) return buy(context, confidence, 0.05 + Math.min(0.05, rvol / 120.0), String.format("Earnings continuation: beat=%s bullish=%s rvol=%.2f", beat, bullish, rvol));
        return hold(context, confidence, String.format("Earnings continuation not ready: confidence=%.2f earnings=%s beat=%s", confidence, earnings, beat));
    }
}
