package com.bot.intelligence;

import java.util.Locale;

public class StockMemoryProfile {
    private final String ticker;
    private double trendScore;
    private double relativeVolumeScore;
    private double catalystSensitivityScore;
    private double newsQualityScore;
    private double predictiveScore;
    private long lastUpdatedAt;
    private long lastNewsAt;
    private long lastBarAt;
    private int newsCount;
    private int highPriorityCatalystCount;
    private String lastHeadline = "";
    private String preferredStrategy = "UNKNOWN";
    private String thesis = "No thesis yet";

    public StockMemoryProfile(String ticker) {
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public String getTicker() { return ticker; }
    public double getTrendScore() { return trendScore; }
    public void setTrendScore(double trendScore) { this.trendScore = clamp(trendScore); touch(); }
    public double getRelativeVolumeScore() { return relativeVolumeScore; }
    public void setRelativeVolumeScore(double relativeVolumeScore) { this.relativeVolumeScore = clamp(relativeVolumeScore); touch(); }
    public double getCatalystSensitivityScore() { return catalystSensitivityScore; }
    public void setCatalystSensitivityScore(double catalystSensitivityScore) { this.catalystSensitivityScore = clamp(catalystSensitivityScore); touch(); }
    public double getNewsQualityScore() { return newsQualityScore; }
    public void setNewsQualityScore(double newsQualityScore) { this.newsQualityScore = clamp(newsQualityScore); touch(); }
    public double getPredictiveScore() { return predictiveScore; }
    public void setPredictiveScore(double predictiveScore) { this.predictiveScore = clamp(predictiveScore); touch(); }
    public long getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(long lastUpdatedAt) { if (lastUpdatedAt > 0) this.lastUpdatedAt = lastUpdatedAt; }
    public long getLastNewsAt() { return lastNewsAt; }
    public void setLastNewsAt(long lastNewsAt) { this.lastNewsAt = lastNewsAt; touch(); }
    public long getLastBarAt() { return lastBarAt; }
    public void setLastBarAt(long lastBarAt) { this.lastBarAt = lastBarAt; touch(); }
    public int getNewsCount() { return newsCount; }
    public void setNewsCount(int newsCount) { this.newsCount = Math.max(0, newsCount); touch(); }
    public void incrementNewsCount() { this.newsCount++; touch(); }
    public int getHighPriorityCatalystCount() { return highPriorityCatalystCount; }
    public void setHighPriorityCatalystCount(int highPriorityCatalystCount) { this.highPriorityCatalystCount = Math.max(0, highPriorityCatalystCount); touch(); }
    public void incrementHighPriorityCatalystCount() { this.highPriorityCatalystCount++; touch(); }
    public String getLastHeadline() { return lastHeadline; }
    public void setLastHeadline(String lastHeadline) { this.lastHeadline = lastHeadline == null ? "" : lastHeadline; touch(); }
    public String getPreferredStrategy() { return preferredStrategy; }
    public void setPreferredStrategy(String preferredStrategy) { this.preferredStrategy = preferredStrategy == null ? "UNKNOWN" : preferredStrategy; touch(); }
    public String getThesis() { return thesis; }
    public void setThesis(String thesis) { this.thesis = thesis == null ? "" : thesis; touch(); }

    public double blendedOpportunityScore() {
        return clamp((trendScore * 0.26) + (relativeVolumeScore * 0.26) + (catalystSensitivityScore * 0.24) + (newsQualityScore * 0.14) + (predictiveScore * 0.10));
    }

    private void touch() {
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
