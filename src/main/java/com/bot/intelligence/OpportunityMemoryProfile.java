package com.bot.intelligence;

import java.util.Locale;

/** Per-ticker personality memory for intraday opportunity behavior. */
public final class OpportunityMemoryProfile {
    private final String ticker;
    private int observations;
    private int catalystObservations;
    private int parabolicObservations;
    private double averageNewsMoveScore;
    private double averagePullbackScore;
    private double averageSecondLegScore;
    private double averageVolatilityScore;
    private double averageLiquidityScore;
    private double strategySuccessScore;
    private long preferredHoldMillis;
    private long lastObservedAt;
    private String bestStrategy = "WATCH_FOR_CONFIRMATION";
    private String personality = "No behavior profile yet";

    public OpportunityMemoryProfile(String ticker) {
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        this.lastObservedAt = System.currentTimeMillis();
        this.preferredHoldMillis = 30L * 60L * 1000L;
    }

    public String getTicker() { return ticker; }
    public int getObservations() { return observations; }
    public void setObservations(int observations) { this.observations = Math.max(0, observations); touch(); }
    public void incrementObservations() { this.observations++; touch(); }
    public int getCatalystObservations() { return catalystObservations; }
    public void setCatalystObservations(int catalystObservations) { this.catalystObservations = Math.max(0, catalystObservations); touch(); }
    public void incrementCatalystObservations() { this.catalystObservations++; touch(); }
    public int getParabolicObservations() { return parabolicObservations; }
    public void setParabolicObservations(int parabolicObservations) { this.parabolicObservations = Math.max(0, parabolicObservations); touch(); }
    public void incrementParabolicObservations() { this.parabolicObservations++; touch(); }
    public double getAverageNewsMoveScore() { return averageNewsMoveScore; }
    public void setAverageNewsMoveScore(double value) { this.averageNewsMoveScore = clamp(value); touch(); }
    public double getAveragePullbackScore() { return averagePullbackScore; }
    public void setAveragePullbackScore(double value) { this.averagePullbackScore = clamp(value); touch(); }
    public double getAverageSecondLegScore() { return averageSecondLegScore; }
    public void setAverageSecondLegScore(double value) { this.averageSecondLegScore = clamp(value); touch(); }
    public double getAverageVolatilityScore() { return averageVolatilityScore; }
    public void setAverageVolatilityScore(double value) { this.averageVolatilityScore = clamp(value); touch(); }
    public double getAverageLiquidityScore() { return averageLiquidityScore; }
    public void setAverageLiquidityScore(double value) { this.averageLiquidityScore = clamp(value); touch(); }
    public double getStrategySuccessScore() { return strategySuccessScore; }
    public void setStrategySuccessScore(double value) { this.strategySuccessScore = clamp(value); touch(); }
    public long getPreferredHoldMillis() { return preferredHoldMillis; }
    public void setPreferredHoldMillis(long preferredHoldMillis) { this.preferredHoldMillis = Math.max(60_000L, preferredHoldMillis); touch(); }
    public long getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(long lastObservedAt) { if (lastObservedAt > 0L) this.lastObservedAt = lastObservedAt; }
    public String getBestStrategy() { return bestStrategy; }
    public void setBestStrategy(String bestStrategy) { this.bestStrategy = bestStrategy == null ? "WATCH_FOR_CONFIRMATION" : bestStrategy; touch(); }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality == null ? "" : personality; touch(); }

    public double opportunityScore() {
        return clamp(averageNewsMoveScore * 0.25 + averageSecondLegScore * 0.22 + averageVolatilityScore * 0.20 + averageLiquidityScore * 0.16 + strategySuccessScore * 0.17);
    }

    public String compactSummary() {
        return ticker + " score=" + fmt(opportunityScore()) +
                " best=" + bestStrategy +
                " newsMove=" + fmt(averageNewsMoveScore) +
                " pullback=" + fmt(averagePullbackScore) +
                " secondLeg=" + fmt(averageSecondLegScore) +
                " vol=" + fmt(averageVolatilityScore) +
                " holdMin=" + (preferredHoldMillis / 60_000L) +
                " personality=" + personality;
    }

    private void touch() {
        this.lastObservedAt = System.currentTimeMillis();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
