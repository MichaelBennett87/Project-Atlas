package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class OfferingFadeShortStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "OFFERING_FADE_SHORT";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!context.hasNews()) return hold(context, 0.0, "No offering catalyst.");
        String text = UnifiedStrategyUtil.lowerNews(context.newsText());
        boolean offering = text.contains("public offering") || text.contains("registered direct") || text.contains("private placement") || text.contains("pre-funded warrants") || text.contains("ordinary shares at $");
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean bearish = UnifiedStrategyUtil.bearishBreak(bars) || TechnicalAnalysis.latestClose(bars) < TechnicalAnalysis.vwap(bars, 40);
        double drop = TechnicalAnalysis.percentDropFromRecentHigh(bars, 20);
        double confidence = TechnicalAnalysis.clamp((offering ? 0.34 : 0.0) + (bearish ? 0.22 : 0.0) + Math.min(1.0, rvol / 4.0) * 0.24 + Math.min(1.0, drop / 0.08) * 0.20);
        if (offering && bearish && rvol >= 1.0 && confidence >= 0.72) return shortSell(context, confidence, Math.max(0.04, drop * 0.8), String.format("Offering/dilution fade short: drop=%.2f%% rvol=%.2f bearish=%s", drop * 100.0, rvol, bearish));
        return hold(context, confidence, String.format("Offering fade not ready: confidence=%.2f offering=%s", confidence, offering));
    }
}
