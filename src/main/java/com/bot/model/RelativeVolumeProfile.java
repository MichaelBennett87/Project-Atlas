package com.bot.model;

public class RelativeVolumeProfile {

    public final String ticker;
    public final double currentVolume;
    public final double averageVolume;
    public final double relativeVolume;
    public final double rvolScore;
    public final boolean usable;
    public final String category;
    public final String reason;

    public RelativeVolumeProfile(
            String ticker,
            double currentVolume,
            double averageVolume,
            double relativeVolume,
            double rvolScore,
            boolean usable,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.currentVolume = currentVolume;
        this.averageVolume = averageVolume;
        this.relativeVolume = relativeVolume;
        this.rvolScore = rvolScore;
        this.usable = usable;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "RelativeVolumeProfile{" +
                "ticker='" + ticker + '\'' +
                ", currentVolume=" + currentVolume +
                ", averageVolume=" + averageVolume +
                ", relativeVolume=" + relativeVolume +
                ", rvolScore=" + rvolScore +
                ", usable=" + usable +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}