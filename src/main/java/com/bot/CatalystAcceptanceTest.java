package com.bot;

import com.bot.model.NewsEvent;
import com.bot.news.FinBertService;
import com.bot.strategy.NewsOpportunityScorer;

public class CatalystAcceptanceTest {

    public static void main(String[] args) throws Exception {

        FinBertService finbert = new FinBertService();
        NewsOpportunityScorer scorer = new NewsOpportunityScorer(finbert);

        NewsEvent event = new NewsEvent(
                "manual-test-001",
                "AAPL",
                "Apple raises guidance after record iPhone revenue and strong demand",
                "Apple reported record revenue, strong demand, and raised full-year guidance.",
                System.currentTimeMillis()
        );

        System.out.println(scorer.score(event));
    }
}