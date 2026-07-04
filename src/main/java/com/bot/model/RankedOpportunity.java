package com.bot.model;

public class RankedOpportunity {

    public final NewsOpportunity opportunity;
    public final MarketQuality marketQuality;
    public final double rankScore;
    public final String reason;

    public RankedOpportunity(
            NewsOpportunity opportunity,
            MarketQuality marketQuality,
            double rankScore,
            String reason
    ) {
        this.opportunity = opportunity;
        this.marketQuality = marketQuality;
        this.rankScore = rankScore;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "RankedOpportunity{" +
                "ticker='" + opportunity.news.getTicker() + '\'' +
                ", headline='" + opportunity.news.getHeadline() + '\'' +
                ", opportunityScore=" + opportunity.finalScore +
                ", marketQuality=" + marketQuality +
                ", rankScore=" + rankScore +
                ", reason='" + reason + '\'' +
                '}';
    }
}