package com.bot.scanner;

public final class TechnicalFeatureSnapshot {
    public final String ticker;
    public final int bars;
    public final double latestPrice;
    public final long latestVolume;
    public final double dollarVolume;
    public final double relativeVolume;
    public final double oneBarVelocityPct;
    public final double threeBarVelocityPct;
    public final double fiveBarVelocityPct;
    public final double tenBarVelocityPct;
    public final double accelerationPct;
    public final double rangePct;
    public final double atrPct;
    public final double vwap;
    public final double priceVsVwapPct;
    public final boolean breakingOut;
    public final boolean breakingDown;

    public TechnicalFeatureSnapshot(String ticker,
                                    int bars,
                                    double latestPrice,
                                    long latestVolume,
                                    double dollarVolume,
                                    double relativeVolume,
                                    double oneBarVelocityPct,
                                    double threeBarVelocityPct,
                                    double fiveBarVelocityPct,
                                    double tenBarVelocityPct,
                                    double accelerationPct,
                                    double rangePct,
                                    double atrPct,
                                    double vwap,
                                    double priceVsVwapPct,
                                    boolean breakingOut,
                                    boolean breakingDown) {
        this.ticker = ticker == null ? "" : ticker;
        this.bars = bars;
        this.latestPrice = latestPrice;
        this.latestVolume = latestVolume;
        this.dollarVolume = dollarVolume;
        this.relativeVolume = relativeVolume;
        this.oneBarVelocityPct = oneBarVelocityPct;
        this.threeBarVelocityPct = threeBarVelocityPct;
        this.fiveBarVelocityPct = fiveBarVelocityPct;
        this.tenBarVelocityPct = tenBarVelocityPct;
        this.accelerationPct = accelerationPct;
        this.rangePct = rangePct;
        this.atrPct = atrPct;
        this.vwap = vwap;
        this.priceVsVwapPct = priceVsVwapPct;
        this.breakingOut = breakingOut;
        this.breakingDown = breakingDown;
    }

    public boolean hasRollingTape() {
        return bars >= 2;
    }
}
