package com.bot.model;

public class NewsFreshnessProfile {

    public final long ageMs;
    public final double freshnessScore;
    public final boolean usable;
    public final String category;
    public final String reason;

    public NewsFreshnessProfile(
            long ageMs,
            double freshnessScore,
            boolean usable,
            String category,
            String reason
    ) {
        this.ageMs = ageMs;
        this.freshnessScore = freshnessScore;
        this.usable = usable;
        this.category = category;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "NewsFreshnessProfile{" +
                "ageMs=" + ageMs +
                ", freshnessScore=" + freshnessScore +
                ", usable=" + usable +
                ", category='" + category + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}