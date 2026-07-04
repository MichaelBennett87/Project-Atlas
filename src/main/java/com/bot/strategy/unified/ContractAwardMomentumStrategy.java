package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class ContractAwardMomentumStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "CONTRACT_AWARD_MOMENTUM";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!context.hasNews()) return hold(context, 0.0, "No contract/award catalyst.");
        String text = UnifiedStrategyUtil.lowerNews(context.newsText());
        boolean contract = text.contains("contract") || text.contains("award") || text.contains("agreement") || text.contains("partnership") || text.contains("purchase order");
        boolean large = text.contains("million") || text.contains("billion") || text.contains("multi-year") || text.contains("seven-year");
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean confirmation = TechnicalAnalysis.bullishBreak(bars) || TechnicalAnalysis.latestClose(bars) > TechnicalAnalysis.vwap(bars, 40);
        double confidence = TechnicalAnalysis.clamp((contract ? 0.24 : 0.0) + (large ? 0.18 : 0.0) + (confirmation ? 0.22 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.24 + (UnifiedStrategyUtil.risingCloses(bars, 2) ? 0.12 : 0.0));
        if (contract && large && confirmation && rvol >= 1.1 && confidence >= 0.68) return buy(context, confidence, 0.045 + Math.min(0.05, rvol / 140.0), String.format("Contract/award momentum: large=%s rvol=%.2f confirmation=%s", large, rvol, confirmation));
        return hold(context, confidence, String.format("Contract momentum not ready: confidence=%.2f contract=%s large=%s", confidence, contract, large));
    }
}
