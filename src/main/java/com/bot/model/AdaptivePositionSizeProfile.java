package com.bot.model;

public class AdaptivePositionSizeProfile {

    public final String ticker;
    public final double confidenceScore;
    public final int baseQuantity;
    public final int finalQuantity;
    public final double sizeMultiplier;
    public final String category;
    public final String reason;

    public AdaptivePositionSizeProfile(
            String ticker,
            double confidenceScore,
            int baseQuantity,
            int finalQuantity,
            double sizeMultiplier,
            String category,
            String reason
    ) {
        this.ticker = ticker;
        this.confidenceScore = confidenceScore;
        this.baseQuantity = baseQuantity;
        this.finalQuantity = finalQuantity;
        this.sizeMultiplier = sizeMultiplier;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "AdaptivePositionSizeProfile{" +
                "ticker='" + ticker + '\'' +
                ", confidenceScore=" + confidenceScore +
                ", baseQuantity=" + baseQuantity +
                ", finalQuantity=" + finalQuantity +
                ", sizeMultiplier=" + sizeMultiplier +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}