package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.EntryDecision;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.model.TradeDirection;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.CatalystClassifier;
import com.bot.strategy.CatalystQualityFilter;
import com.bot.strategy.EntryDecisionService;
import com.bot.strategy.OpportunityRanker;
import com.bot.strategy.TradeDirectionService;

import java.util.List;

public class EndToEndPaperTradingSimulationTest {

    public static void main(String[] args) {
        AlpacaBroker broker =
                new AlpacaBroker(true);

        MarketDataCache marketData =
                new MarketDataCache();

        TradeJournal tradeJournal =
                new TradeJournal();

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        tradeJournal
                );

        PositionManager positionManager =
                new PositionManager(
                        marketData,
                        orderExecutor
                );

        positionManager.syncFromBroker(
                broker.getOpenPositions()
        );

        AccountService accountService =
                new AlpacaAccountService(
                        broker
                );

        AdvancedRiskEngine riskEngine =
                new AdvancedRiskEngine(
                        accountService,
                        positionManager
                );

        CatalystClassifier catalystClassifier =
                new CatalystClassifier();

        CatalystQualityFilter catalystQualityFilter =
                new CatalystQualityFilter();

        OpportunityRanker opportunityRanker =
                new OpportunityRanker(
                        broker
                );

        EntryDecisionService entryDecisionService =
                new EntryDecisionService();

        TradeDirectionService tradeDirectionService =
                new TradeDirectionService();

        NewsEvent news =
                new NewsEvent(
                        "end-to-end-paper-test-smci-1",
                        "SMCI",
                        "SMCI raises guidance after record AI server demand",
                        "Super Micro Computer raised full-year guidance after reporting record AI server demand, strong revenue growth, and expanding customer orders.",
                        System.currentTimeMillis()
                );

        SentimentScore sentiment =
                new SentimentScore(
                        0.95,
                        0.03,
                        0.02
                );

        CatalystResult catalyst =
                catalystClassifier.classify(
                        news
                );

        CatalystQualityDecision qualityDecision =
                catalystQualityFilter.evaluate(
                        catalyst,
                        sentiment
                );

        double finalScore =
                calculateFinalScore(
                        sentiment,
                        catalyst,
                        qualityDecision
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        finalScore,
                        qualityDecision.passed,
                        qualityDecision.reason
                );

        List<RankedOpportunity> rankedOpportunities =
                opportunityRanker.rank(
                        List.of(opportunity)
                );

        RankedOpportunity rankedOpportunity =
                rankedOpportunities.isEmpty()
                        ? null
                        : rankedOpportunities.get(0);

        EntryDecision decision =
                rankedOpportunity == null
                        ? EntryDecision.REJECT
                        : entryDecisionService.decide(
                        rankedOpportunity,
                        RelevanceDecision.PRIMARY_SUBJECT
                );

        TradeDirection direction =
                tradeDirectionService.resolve(
                        catalyst,
                        sentiment
                );

        boolean riskAllowed =
                riskEngine.allowNewTrade(
                        news.getTicker()
                );

        int quantity =
                riskEngine.calculateQuantity(
                        news.getTicker(),
                        catalyst,
                        sentiment
                );

        boolean orderFilled =
                false;

        if (decision == EntryDecision.IMMEDIATE_ENTRY &&
                direction == TradeDirection.LONG_STOCK &&
                riskAllowed &&
                quantity > 0) {

            orderFilled =
                    orderExecutor.buyMarketAndWaitForFill(
                            news.getTicker(),
                            quantity
                    );

            if (orderFilled) {
                double entryPrice =
                        riskEngine.lastPrice(
                                news.getTicker()
                        );

                positionManager.open(
                        news.getTicker(),
                        entryPrice,
                        quantity
                );
            }
        }

        boolean positionOpened =
                positionManager.hasOpenPosition(
                        news.getTicker()
                );

        boolean passed =
                qualityDecision.passed &&
                        rankedOpportunity != null &&
                        decision == EntryDecision.IMMEDIATE_ENTRY &&
                        direction == TradeDirection.LONG_STOCK &&
                        riskAllowed &&
                        quantity > 0 &&
                        orderFilled &&
                        positionOpened;

        System.out.println();
        System.out.println("=== END TO END PAPER TRADING SIMULATION TEST ===");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Headline: " + news.getHeadline());
        System.out.println("Sentiment: " + sentiment);
        System.out.println("Catalyst: " + catalyst);
        System.out.println("Quality Decision: " + qualityDecision);
        System.out.println("Final Score: " + finalScore);
        System.out.println("Ranked Opportunity: " + rankedOpportunity);
        System.out.println("Entry Decision: " + decision);
        System.out.println("Trade Direction: " + direction);
        System.out.println("Risk Allowed: " + riskAllowed);
        System.out.println("Quantity: " + quantity);
        System.out.println("Order Filled: " + orderFilled);
        System.out.println("Position Opened: " + positionOpened);
        System.out.println();
        System.out.println("Expected final result: PASS");
        System.out.println("Actual final result: " + (passed ? "PASS" : "FAIL"));

        if (!passed) {
            throw new IllegalStateException(
                    "End-to-end paper trading simulation failed."
            );
        }
    }

    private static double calculateFinalScore(
            SentimentScore sentiment,
            CatalystResult catalyst,
            CatalystQualityDecision qualityDecision
    ) {
        if (!qualityDecision.passed) {
            return -999.0;
        }

        return (sentiment.netSentiment() * 0.65)
                + (sentiment.positive * 0.20)
                + (catalyst.weight * 0.75)
                - (sentiment.negative * 0.50);
    }
}