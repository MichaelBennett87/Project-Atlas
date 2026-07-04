package com.bot.model;

public class Level2OrderBookProfile {

    public final String ticker;
    public final double bidSize;
    public final double askSize;
    public final double imbalance;
    public final double spreadPercent;
    public final double level2Score;
    public final boolean usable;
    public final String category;
    public final String reason;

    public Level2OrderBookProfile(
            String ticker,
            double bidSize,
            double askSize,
            double imbalance,
            double spreadPercent,
            double level2Score,
            boolean usable,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.bidSize = bidSize;
        this.askSize = askSize;
        this.imbalance = imbalance;
        this.spreadPercent = spreadPercent;
        this.level2Score = level2Score;
        this.usable = usable;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "Level2OrderBookProfile{" +
                "ticker='" + ticker + '\'' +
                ", bidSize=" + bidSize +
                ", askSize=" + askSize +
                ", imbalance=" + imbalance +
                ", spreadPercent=" + spreadPercent +
                ", level2Score=" + level2Score +
                ", usable=" + usable +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}