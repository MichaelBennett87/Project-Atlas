package com.bot.intelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks symbols before a catalyst arrives so the live engine can focus on names
 * that are already primed for a meaningful reaction.
 */
public final class PredictiveOpportunityRanker {
    private static final PredictiveOpportunityRanker INSTANCE = new PredictiveOpportunityRanker();

    private final StockMemoryService memory = StockMemoryService.getInstance();
    private volatile long lastLogAt = 0L;

    private PredictiveOpportunityRanker() {
    }

    public static PredictiveOpportunityRanker getInstance() {
        return INSTANCE;
    }

    public double score(String ticker) {
        StockMemoryProfile profile = memory.profile(ticker);
        MarketRegimeSnapshot regime = MarketRegimeEngine.getInstance().currentSnapshot();
        double base = profile.blendedOpportunityScore();
        double regimeBoost = 0.0;
        switch (regime.getRegime()) {
            case STRONG_UPTREND:
            case UPTREND:
                regimeBoost = Math.max(profile.getTrendScore(), profile.getRelativeVolumeScore()) * 0.10;
                break;
            case HIGH_VOLATILITY:
            case PANIC:
                regimeBoost = profile.getRelativeVolumeScore() * 0.08;
                break;
            case DOWNTREND:
                regimeBoost = profile.getCatalystSensitivityScore() * 0.05;
                break;
            default:
                regimeBoost = 0.0;
        }
        return clamp(base + regimeBoost);
    }

    public List<String> topTickers(int limit) {
        List<StockMemoryProfile> profiles = new ArrayList<>(memory.topProfiles(Math.max(limit * 3, limit)));
        profiles.sort(Comparator.comparingDouble((StockMemoryProfile p) -> score(p.getTicker())).reversed());
        List<String> tickers = new ArrayList<>();
        for (StockMemoryProfile profile : profiles) {
            if (tickers.size() >= limit) break;
            if (profile.getTicker() != null && !profile.getTicker().isBlank()) {
                tickers.add(profile.getTicker());
            }
        }
        maybeLog(tickers);
        return tickers;
    }

    private void maybeLog(List<String> tickers) {
        long now = System.currentTimeMillis();
        if (now - lastLogAt < envLong("PREDICTIVE_RANKER_LOG_INTERVAL_MS", 180_000L)) {
            return;
        }
        lastLogAt = now;
        System.out.println("PREDICTIVE OPPORTUNITY RANKER: top=" + tickers.subList(0, Math.min(10, tickers.size())));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
