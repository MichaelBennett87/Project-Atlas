package com.bot.model;

public class GapProfile {

    public final String ticker;
    public final double previousClose;
    public final double currentPrice;
    public final double gapPercent;
    public final double gapScore;
    public final boolean usable;
    public final String category;
    public final String reason;

    public GapProfile(
            String ticker,
            double previousClose,
            double currentPrice,
            double gapPercent,
            double gapScore,
            boolean usable,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.previousClose = previousClose;
        this.currentPrice = currentPrice;
        this.gapPercent = gapPercent;
        this.gapScore = gapScore;
        this.usable = usable;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "GapProfile{" +
                "ticker='" + ticker + '\'' +
                ", previousClose=" + previousClose +
                ", currentPrice=" + currentPrice +
                ", gapPercent=" + gapPercent +
                ", gapScore=" + gapScore +
                ", usable=" + usable +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}