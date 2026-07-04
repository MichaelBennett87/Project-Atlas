package com.bot.model;

import com.bot.sentiment.SentimentScore;

public class NewsOpportunity {

    public final NewsEvent news;
    public final SentimentScore sentiment;
    public final CatalystResult catalyst;
    public final double finalScore;
    public final boolean qualityPassed;
    public final String rejectionReason;

    public NewsOpportunity(
            NewsEvent news,
            SentimentScore sentiment,
            CatalystResult catalyst,
            double finalScore,
            boolean qualityPassed,
            String rejectionReason
    ) {
        this.news = news;
        this.sentiment = sentiment;
        this.catalyst = catalyst;
        this.finalScore = finalScore;
        this.qualityPassed = qualityPassed;
        this.rejectionReason = rejectionReason;
    }

    @Override
    public String toString() {
        return "NewsOpportunity{" +
                "ticker='" + news.getTicker() + '\'' +
                ", headline='" + news.getHeadline() + '\'' +
                ", sentiment=" + sentiment +
                ", catalyst=" + catalyst +
                ", finalScore=" + finalScore +
                ", qualityPassed=" + qualityPassed +
                ", rejectionReason='" + rejectionReason + '\'' +
                '}';
    }
}