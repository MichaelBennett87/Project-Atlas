package com.bot.model;

public class MarketRegimeProfile {

    public final int sampleSize;
    public final double winRate;
    public final double averageGain;
    public final double regimeScore;
    public final String category;
    public final String reason;

    public MarketRegimeProfile(
            int sampleSize,
            double winRate,
            double averageGain,
            double regimeScore,
            String category,
            String reason
    ) {
        this.sampleSize = sampleSize;
        this.winRate = winRate;
        this.averageGain = averageGain;
        this.regimeScore = regimeScore;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "MarketRegimeProfile{" +
                "sampleSize=" + sampleSize +
                ", winRate=" + winRate +
                ", averageGain=" + averageGain +
                ", regimeScore=" + regimeScore +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}