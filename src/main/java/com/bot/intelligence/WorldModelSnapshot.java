package com.bot.intelligence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot of the bot's current market world model.
 *
 * This is not a trading strategy. It is shared context for the agents so they
 * reason about the same market state instead of each strategy inventing its own
 * incomplete regime view.
 */
public final class WorldModelSnapshot {
    private final MarketRegime regime;
    private final double trendScore;
    private final double volatilityScore;
    private final double liquidityScore;
    private final double smallCapLeadershipScore;
    private final double largeCapLeadershipScore;
    private final double catalystHeatScore;
    private final double newsFlowScore;
    private final double parabolicHeatScore;
    private final double dataConfidenceScore;
    private final long updatedAt;
    private final String summary;
    private final Map<String, Double> sectorHeat;

    public WorldModelSnapshot(
            MarketRegime regime,
            double trendScore,
            double volatilityScore,
            double liquidityScore,
            double smallCapLeadershipScore,
            double largeCapLeadershipScore,
            double catalystHeatScore,
            double newsFlowScore,
            double parabolicHeatScore,
            double dataConfidenceScore,
            long updatedAt,
            String summary,
            Map<String, Double> sectorHeat
    ) {
        this.regime = regime == null ? MarketRegime.UNKNOWN : regime;
        this.trendScore = clamp(trendScore);
        this.volatilityScore = clamp(volatilityScore);
        this.liquidityScore = clamp(liquidityScore);
        this.smallCapLeadershipScore = clamp(smallCapLeadershipScore);
        this.largeCapLeadershipScore = clamp(largeCapLeadershipScore);
        this.catalystHeatScore = clamp(catalystHeatScore);
        this.newsFlowScore = clamp(newsFlowScore);
        this.parabolicHeatScore = clamp(parabolicHeatScore);
        this.dataConfidenceScore = clamp(dataConfidenceScore);
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
        this.summary = summary == null ? "" : summary;
        this.sectorHeat = sectorHeat == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sectorHeat));
    }

    public static WorldModelSnapshot unknown() {
        return new WorldModelSnapshot(
                MarketRegime.UNKNOWN,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                System.currentTimeMillis(),
                "No world model data yet.",
                Collections.emptyMap()
        );
    }

    public MarketRegime getRegime() { return regime; }
    public double getTrendScore() { return trendScore; }
    public double getVolatilityScore() { return volatilityScore; }
    public double getLiquidityScore() { return liquidityScore; }
    public double getSmallCapLeadershipScore() { return smallCapLeadershipScore; }
    public double getLargeCapLeadershipScore() { return largeCapLeadershipScore; }
    public double getCatalystHeatScore() { return catalystHeatScore; }
    public double getNewsFlowScore() { return newsFlowScore; }
    public double getParabolicHeatScore() { return parabolicHeatScore; }
    public double getDataConfidenceScore() { return dataConfidenceScore; }
    public long getUpdatedAt() { return updatedAt; }
    public String getSummary() { return summary; }
    public Map<String, Double> getSectorHeat() { return sectorHeat; }

    public String compactSummary() {
        return "regime=" + regime +
                " trend=" + fmt(trendScore) +
                " vol=" + fmt(volatilityScore) +
                " liquidity=" + fmt(liquidityScore) +
                " smallCap=" + fmt(smallCapLeadershipScore) +
                " largeCap=" + fmt(largeCapLeadershipScore) +
                " catalystHeat=" + fmt(catalystHeatScore) +
                " newsFlow=" + fmt(newsFlowScore) +
                " parabolicHeat=" + fmt(parabolicHeatScore) +
                " dataConfidence=" + fmt(dataConfidenceScore) +
                " summary=" + summary;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
