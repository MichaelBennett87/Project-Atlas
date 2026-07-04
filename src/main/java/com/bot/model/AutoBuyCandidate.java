package com.bot.model;

public class AutoBuyCandidate {

    public final RankedOpportunity rankedOpportunity;
    public final FloatProfile floatProfile;
    public final double autoBuyScore;
    public final String reason;
    public final long createdAt;

    public AutoBuyCandidate(
            RankedOpportunity rankedOpportunity,
            FloatProfile floatProfile,
            double autoBuyScore,
            String reason
    ) {
        this.rankedOpportunity = rankedOpportunity;
        this.floatProfile = floatProfile;
        this.autoBuyScore = autoBuyScore;
        this.reason = reason;
        this.createdAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "AutoBuyCandidate{" +
                "ticker='" + rankedOpportunity.opportunity.news.getTicker() + '\'' +
                ", autoBuyScore=" + autoBuyScore +
                ", floatProfile=" + floatProfile +
                ", reason='" + reason + '\'' +
                ", headline='" + rankedOpportunity.opportunity.news.getHeadline() + '\'' +
                '}';
    }
}