package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.PendingSignal;
import com.bot.news.FinBertService;
import com.bot.strategy.MarketConfirmationFilter;
import com.bot.strategy.NewsOpportunityScorer;
import com.bot.strategy.PendingSignalQueue;

import java.util.List;

public class EndToEndSignalSimulationTest {

    public static void main(String[] args) throws Exception {

        System.out.println("=== END TO END SIGNAL SIMULATION TEST ===");

        String ticker = "TEST";

        FinBertService finbert =
                new FinBertService();

        NewsOpportunityScorer scorer =
                new NewsOpportunityScorer(finbert);

        MarketDataCache marketData =
                new MarketDataCache();

        MarketConfirmationFilter confirmation =
                new MarketConfirmationFilter(marketData);

        PendingSignalQueue queue =
                new PendingSignalQueue(5 * 60_000L);

        NewsEvent news =
                new NewsEvent(
                        "simulation-001",
                        ticker,
                        "Test company raises guidance after record revenue and strong demand",
                        "The company reported record revenue, strong demand, and raised full-year guidance after accelerating growth.",
                        System.currentTimeMillis()
                );

        NewsOpportunity opportunity =
                scorer.score(news);

        System.out.println();
        System.out.println("=== NEWS OPPORTUNITY ===");
        System.out.println(opportunity);

        if (!opportunity.qualityPassed) {
            System.out.println("TEST FAILED: opportunity did not pass quality filter.");
            return;
        }

        queue.add(opportunity);

        List<PendingSignal> activeSignals =
                queue.activeSignals();

        if (activeSignals.isEmpty()) {
            System.out.println("TEST FAILED: pending signal was not added.");
            return;
        }

        System.out.println();
        System.out.println("=== PENDING SIGNAL QUEUE ===");
        System.out.println("Pending signals: " + activeSignals.size());

        System.out.println();
        System.out.println("=== FLAT MARKET CONFIRMATION CHECK ===");

        for (int i = 0; i < 70; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00,
                    1000
            );
        }

        boolean flatConfirmed =
                confirmation.confirm(ticker);

        System.out.println("Flat confirmed: " + flatConfirmed);

        if (flatConfirmed) {
            System.out.println("TEST FAILED: flat market should not confirm.");
            return;
        }

        System.out.println();
        System.out.println("=== MOMENTUM + VOLUME CONFIRMATION CHECK ===");

        marketData =
                new MarketDataCache();

        confirmation =
                new MarketConfirmationFilter(marketData);

        for (int i = 0; i < 69; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    1000
            );
        }

        addBar(
                marketData,
                ticker,
                100.00 + (69 * 0.02),
                2500
        );

        boolean momentumConfirmed =
                confirmation.confirm(ticker);

        System.out.println("Momentum confirmed: " + momentumConfirmed);

        if (!momentumConfirmed) {
            System.out.println("TEST FAILED: momentum + volume should confirm.");
            return;
        }

        System.out.println();
        System.out.println("TEST PASSED: full simulated signal path works.");
    }

    private static void addBar(
            MarketDataCache marketData,
            String ticker,
            double price,
            long volume
    ) {
        marketData.addBar(
                ticker,
                price,
                price,
                price,
                price,
                volume
        );
    }
}