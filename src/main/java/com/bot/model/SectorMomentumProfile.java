package com.bot.model;

public class SectorMomentumProfile {

    public final String ticker;
    public final String sector;
    public final double sectorMomentum;
    public final double sectorScore;
    public final boolean usable;
    public final String category;
    public final String reason;

    public SectorMomentumProfile(
            String ticker,
            String sector,
            double sectorMomentum,
            double sectorScore,
            boolean usable,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.sector = sector;
        this.sectorMomentum = sectorMomentum;
        this.sectorScore = sectorScore;
        this.usable = usable;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "SectorMomentumProfile{" +
                "ticker='" + ticker + '\'' +
                ", sector='" + sector + '\'' +
                ", sectorMomentum=" + sectorMomentum +
                ", sectorScore=" + sectorScore +
                ", usable=" + usable +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}