package com.bot.model;

public class MarketQuality {

    public final String ticker;
    public final double price;
    public final double bid;
    public final double ask;
    public final double spreadPercent;
    public final boolean tradable;
    public final double qualityScore;

    public final double latestTradePrice;
    public final long dayVolume;
    public final long previousDayVolume;
    public final double relativeVolume;
    public final double dollarVolume;
    public final String reason;

    public MarketQuality(
            String ticker,
            double price,
            double bid,
            double ask,
            double spreadPercent,
            boolean tradable,
            double qualityScore
    ) {
        this(
                ticker,
                price,
                bid,
                ask,
                spreadPercent,
                tradable,
                qualityScore,
                price,
                0L,
                0L,
                0.0,
                0.0,
                ""
        );
    }

    public MarketQuality(
            String ticker,
            double price,
            double bid,
            double ask,
            double spreadPercent,
            boolean tradable,
            double qualityScore,
            double latestTradePrice,
            long dayVolume,
            long previousDayVolume,
            double relativeVolume,
            double dollarVolume,
            String reason
    ) {
        this.ticker = ticker;
        this.price = price;
        this.bid = bid;
        this.ask = ask;
        this.spreadPercent = spreadPercent;
        this.tradable = tradable;
        this.qualityScore = qualityScore;
        this.latestTradePrice = latestTradePrice;
        this.dayVolume = dayVolume;
        this.previousDayVolume = previousDayVolume;
        this.relativeVolume = relativeVolume;
        this.dollarVolume = dollarVolume;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "MarketQuality{" +
                "ticker='" + ticker + '\'' +
                ", price=" + price +
                ", bid=" + bid +
                ", ask=" + ask +
                ", spreadPercent=" + spreadPercent +
                ", tradable=" + tradable +
                ", qualityScore=" + qualityScore +
                ", latestTradePrice=" + latestTradePrice +
                ", dayVolume=" + dayVolume +
                ", previousDayVolume=" + previousDayVolume +
                ", relativeVolume=" + relativeVolume +
                ", dollarVolume=" + dollarVolume +
                ", reason='" + reason + '\'' +
                '}';
    }
}