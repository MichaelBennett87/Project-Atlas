package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.MarketDataCache;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.EntryDecisionService;

public class TradeExecutionV1Test {

    public static void main(String[] args) {

        System.out.println("=== TRADE EXECUTION V1 TEST ===");

        String ticker = "SMCI";

        AlpacaBroker broker =
                new AlpacaBroker(true);

        TradeJournal tradeJournal =
                new TradeJournal();

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        tradeJournal
                );

        MarketDataCache marketData =
                new MarketDataCache();

        PositionManager positionManager =
                new PositionManager(
                        marketData,
                        orderExecutor
                );

        EntryDecisionService entryDecisionService =
                new EntryDecisionService();

        RankedOpportunity rankedOpportunity =
                createAutoBuyRankedOpportunity(ticker);

        EntryDecision decision =
                entryDecisionService.decide(rankedOpportunity);

        System.out.println();
        System.out.println("=== ENTRY DECISION ===");
        System.out.println("Expected: " + EntryDecision.IMMEDIATE_ENTRY);
        System.out.println("Actual: " + decision);
        System.out.println(decision == EntryDecision.IMMEDIATE_ENTRY ? "PASS" : "FAIL");

        if (decision != EntryDecision.IMMEDIATE_ENTRY) {
            System.out.println("TEST FAILED: auto-buy candidate did not qualify.");
            return;
        }

        System.out.println();
        System.out.println("=== BUILD INITIAL MOMENTUM ===");

        double entryPrice = 100.00;
        int quantity = 10;

        for (int i = 0; i < 70; i++) {
            double price =
                    entryPrice + (i * 0.03);

            addBar(
                    marketData,
                    ticker,
                    price,
                    3000
            );
        }

        System.out.println("Initial momentum bars loaded.");

        System.out.println();
        System.out.println("=== DRY-RUN BUY ===");

        boolean bought =
                orderExecutor.buyMarketAndWaitForFill(
                        ticker,
                        quantity
                );

        System.out.println("Expected buy filled: true");
        System.out.println("Actual buy filled: " + bought);
        System.out.println(bought ? "PASS" : "FAIL");

        if (!bought) {
            System.out.println("TEST FAILED: dry-run buy was not filled.");
            return;
        }

        positionManager.open(
                ticker,
                entryPrice,
                quantity
        );

        boolean positionOpened =
                positionManager.hasPosition(ticker);

        System.out.println();
        System.out.println("=== POSITION OPEN ===");
        System.out.println("Expected open position: true");
        System.out.println("Actual open position: " + positionOpened);
        System.out.println(positionOpened ? "PASS" : "FAIL");

        if (!positionOpened) {
            System.out.println("TEST FAILED: position did not open.");
            return;
        }

        System.out.println();
        System.out.println("=== PRICE RISES ===");

        for (int i = 0; i < 20; i++) {
            double price =
                    102.10 + (i * 0.04);

            addBarAndNotify(
                    marketData,
                    positionManager,
                    ticker,
                    price,
                    3000
            );
        }

        boolean stillOpenAfterRise =
                positionManager.hasPosition(ticker);

        System.out.println("Expected still open after rise: true");
        System.out.println("Actual still open after rise: " + stillOpenAfterRise);
        System.out.println(stillOpenAfterRise ? "PASS" : "FAIL");

        if (!stillOpenAfterRise) {
            System.out.println("TEST FAILED: position closed while still rising.");
            return;
        }

        System.out.println();
        System.out.println("=== MOMENTUM FADES ===");

        for (int i = 0; i < 40; i++) {
            double price =
                    102.90 - (i * 0.04);

            addBarAndNotify(
                    marketData,
                    positionManager,
                    ticker,
                    price,
                    3000
            );
        }

        boolean positionClosed =
                !positionManager.hasPosition(ticker);

        System.out.println();
        System.out.println("=== POSITION CLOSE ===");
        System.out.println("Expected position closed: true");
        System.out.println("Actual position closed: " + positionClosed);
        System.out.println(positionClosed ? "PASS" : "FAIL");

        if (!positionClosed) {
            System.out.println("TEST FAILED: momentum fade did not close the position.");
            return;
        }

        System.out.println();
        System.out.println("TEST PASSED: auto-buy → dry-run buy → position open → momentum fade → dry-run sell → position closed.");
    }

    private static RankedOpportunity createAutoBuyRankedOpportunity(String ticker) {
        NewsEvent news =
                new NewsEvent(
                        "trade-execution-v1-test",
                        ticker,
                        ticker + " raises guidance after record revenue and strong demand",
                        "The company raised full-year guidance after record revenue, strong demand, and accelerating growth.",
                        System.currentTimeMillis()
                );

        SentimentScore sentiment =
                new SentimentScore(
                        0.95,
                        0.03,
                        0.02
                );

        CatalystResult catalyst =
                new CatalystResult(
                        CatalystType.GUIDANCE_RAISE,
                        0.90,
                        "Test guidance raise catalyst"
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        1.35,
                        true,
                        "Test opportunity passed"
                );

        MarketQuality marketQuality =
                new MarketQuality(
                        ticker,
                        100.00,
                        99.95,
                        100.05,
                        0.001,
                        true,
                        0.90
                );

        return new RankedOpportunity(
                opportunity,
                marketQuality,
                0.95,
                "Test ranked auto-buy opportunity"
        );
    }

    private static void addBarAndNotify(
            MarketDataCache marketData,
            PositionManager positionManager,
            String ticker,
            double price,
            long volume
    ) {
        addBar(
                marketData,
                ticker,
                price,
                volume
        );

        positionManager.onPrice(
                ticker,
                price
        );
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