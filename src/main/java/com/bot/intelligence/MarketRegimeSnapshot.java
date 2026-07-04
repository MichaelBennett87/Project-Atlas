package com.bot.intelligence;

public class MarketRegimeSnapshot {
    private final MarketRegime regime;
    private final double trendScore;
    private final double volatilityScore;
    private final double liquidityScore;
    private final long updatedAt;
    private final String reason;

    public MarketRegimeSnapshot(MarketRegime regime, double trendScore, double volatilityScore, double liquidityScore, long updatedAt, String reason) {
        this.regime = regime == null ? MarketRegime.UNKNOWN : regime;
        this.trendScore = trendScore;
        this.volatilityScore = volatilityScore;
        this.liquidityScore = liquidityScore;
        this.updatedAt = updatedAt;
        this.reason = reason == null ? "" : reason;
    }

    public static MarketRegimeSnapshot unknown() {
        return new MarketRegimeSnapshot(MarketRegime.UNKNOWN, 0.0, 0.0, 0.0, System.currentTimeMillis(), "No regime data yet");
    }

    public MarketRegime getRegime() { return regime; }
    public double getTrendScore() { return trendScore; }
    public double getVolatilityScore() { return volatilityScore; }
    public double getLiquidityScore() { return liquidityScore; }
    public long getUpdatedAt() { return updatedAt; }
    public String getReason() { return reason; }
}
