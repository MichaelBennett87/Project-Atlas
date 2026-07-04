package com.bot.model;

public class MarketCapProfile {

    public final String ticker;
    public final long marketCap;
    public final double marketCapScore;
    public final boolean known;
    public final String category;
    public final String reason;

    public MarketCapProfile(
            String ticker,
            long marketCap,
            double marketCapScore,
            boolean known,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.marketCap = marketCap;
        this.marketCapScore = marketCapScore;
        this.known = known;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "MarketCapProfile{" +
                "ticker='" + ticker + '\'' +
                ", marketCap=" + marketCap +
                ", marketCapScore=" + marketCapScore +
                ", known=" + known +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}