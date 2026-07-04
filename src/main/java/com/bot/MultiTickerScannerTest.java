package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.model.NewsOpportunity;
import com.bot.news.FinBertService;
import com.bot.strategy.MultiTickerNewsScanner;
import com.bot.strategy.NewsOpportunityScorer;

import java.util.List;

public class MultiTickerScannerTest {

    public static void main(String[] args) throws Exception {

        AlpacaBroker broker = new AlpacaBroker();

        FinBertService finbert = new FinBertService();

        NewsOpportunityScorer scorer =
                new NewsOpportunityScorer(finbert);

        MultiTickerNewsScanner scanner =
                new MultiTickerNewsScanner(
                        broker,
                        scorer
                );

        List<NewsOpportunity> opportunities =
                scanner.scan(10);

        System.out.println("Opportunities found: " + opportunities.size());

        for (NewsOpportunity opportunity : opportunities) {
            System.out.println("---");
            System.out.println("Ticker: " + opportunity.news.getTicker());
            System.out.println("Score: " + opportunity.finalScore);
            System.out.println("Passed: " + opportunity.qualityPassed);
            System.out.println("Reason: " + opportunity.rejectionReason);
            System.out.println("Sentiment: " + opportunity.sentiment);
            System.out.println("Catalyst: " + opportunity.catalyst);
            System.out.println("Headline: " + opportunity.news.getHeadline());
        }
    }
}