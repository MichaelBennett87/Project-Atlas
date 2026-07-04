package com.bot.intelligence;

import java.util.ArrayList;
import java.util.List;

/** Builds aligned technical vectors across short/intraday/longer replay windows. */
public final class MultiTimeframeIntelligenceEngine {
    private final TechnicalIntelligenceEngine technical = new TechnicalIntelligenceEngine();

    public List<TechnicalFeatureVector> compute(String ticker, List<HistoricalMarketDataRepository.HistoricalBar> bars) {
        List<TechnicalFeatureVector> out = new ArrayList<>();
        if (bars == null || bars.isEmpty()) return out;
        int n = bars.size();
        add(out, ticker, "5bar", bars, Math.min(5, n));
        add(out, ticker, "15bar", bars, Math.min(15, n));
        add(out, ticker, "30bar", bars, Math.min(30, n));
        add(out, ticker, "90bar", bars, Math.min(90, n));
        add(out, ticker, "full", bars, n);
        return out;
    }

    private void add(List<TechnicalFeatureVector> out, String ticker, String label, List<HistoricalMarketDataRepository.HistoricalBar> bars, int len) {
        if (len <= 1) return;
        out.add(technical.compute(ticker, label, bars.subList(Math.max(0, bars.size() - len), bars.size())));
    }
}
