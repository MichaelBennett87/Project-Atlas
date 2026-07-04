package com.bot;

import com.bot.broker.AlpacaAccountService;
import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.AccountService;
import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.EntryDecision;
import com.bot.model.Level2OrderBookProfile;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.model.SectorMomentumProfile;
import com.bot.model.TradeDirection;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.CatalystClassifier;
import com.bot.strategy.CatalystQualityFilter;
import com.bot.strategy.EntryDecisionService;
import com.bot.strategy.OpportunityRanker;
import com.bot.strategy.TradeDirectionService;

import java.util.List;

public class FullLifecycleSimulationTest {

    public static void main(String[] args) {
        runFullLifecycleSimulationTest();
    }

    private static void runFullLifecycleSimulationTest() {
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
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        ticker -> strongLevel2(ticker),
                        ticker -> strongSector(ticker)
                );

        TradeDirectionService tradeDirectionService =
                new TradeDirectionService();

        NewsEvent news =
                new NewsEvent(
                        "full-lifecycle-smci-1",
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

        AdaptivePositionSizeProfile adaptiveSizeProfile =
                rankedOpportunity == null
                        ? null
                        : entryDecisionService.adaptiveSize(
                        rankedOpportunity,
                        RelevanceDecision.PRIMARY_SUBJECT,
                        10
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

        int riskQuantity =
                riskEngine.calculateQuantity(
                        news.getTicker(),
                        catalyst,
                        sentiment
                );

        int adaptiveQuantity =
                adaptiveSizeProfile == null
                        ? 0
                        : adaptiveSizeProfile.finalQuantity;

        int finalQuantity =
                Math.max(
                        1,
                        Math.min(
                                riskQuantity,
                                adaptiveQuantity
                        )
                );

        boolean buyFilled =
                false;

        if (decision == EntryDecision.IMMEDIATE_ENTRY &&
                direction == TradeDirection.LONG_STOCK &&
                riskAllowed &&
                finalQuantity > 0) {

            buyFilled =
                    orderExecutor.buyMarketAndWaitForFill(
                            news.getTicker(),
                            finalQuantity
                    );

            if (buyFilled) {
                positionManager.open(
                        news.getTicker(),
                        100.00,
                        finalQuantity
                );
            }
        }

        boolean positionOpenedAfterBuy =
                positionManager.hasOpenPosition(
                        news.getTicker()
                );

        double peakPrice =
                110.00;

        positionManager.onPrice(
                news.getTicker(),
                peakPrice
        );

        boolean positionStillOpenAtPeak =
                positionManager.hasOpenPosition(
                        news.getTicker()
                );

        double exitTriggerPrice =
                104.40;

        double dropFromPeak =
                (exitTriggerPrice - peakPrice) / peakPrice;

        positionManager.onPrice(
                news.getTicker(),
                exitTriggerPrice
        );

        boolean positionClosedAfterDrop =
                !positionManager.hasOpenPosition(
                        news.getTicker()
                );

        boolean passed =
                qualityDecision.passed &&
                        rankedOpportunity != null &&
                        decision == EntryDecision.IMMEDIATE_ENTRY &&
                        adaptiveSizeProfile != null &&
                        adaptiveSizeProfile.finalQuantity > 0 &&
                        direction == TradeDirection.LONG_STOCK &&
                        riskAllowed &&
                        finalQuantity > 0 &&
                        buyFilled &&
                        positionOpenedAfterBuy &&
                        positionStillOpenAtPeak &&
                        dropFromPeak <= -0.05 &&
                        positionClosedAfterDrop;

        System.out.println();
        System.out.println("=== FULL LIFECYCLE SIMULATION TEST ===");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Headline: " + news.getHeadline());
        System.out.println("Sentiment: " + sentiment);
        System.out.println("Catalyst: " + catalyst);
        System.out.println("Quality Decision: " + qualityDecision);
        System.out.println("Final Score: " + finalScore);
        System.out.println("Ranked Opportunity: " + rankedOpportunity);
        System.out.println("Entry Decision: " + decision);
        System.out.println("Adaptive Size Profile: " + adaptiveSizeProfile);
        System.out.println("Trade Direction: " + direction);
        System.out.println("Risk Allowed: " + riskAllowed);
        System.out.println("Risk Quantity: " + riskQuantity);
        System.out.println("Adaptive Quantity: " + adaptiveQuantity);
        System.out.println("Final Quantity Used: " + finalQuantity);
        System.out.println("Buy Filled: " + buyFilled);
        System.out.println("Position Opened After Buy: " + positionOpenedAfterBuy);
        System.out.println("Peak Price: " + peakPrice);
        System.out.println("Exit Trigger Price: " + exitTriggerPrice);
        System.out.println("Drop From Peak: " + dropFromPeak);
        System.out.println("Position Still Open At Peak: " + positionStillOpenAtPeak);
        System.out.println("Position Closed After Drop: " + positionClosedAfterDrop);
        System.out.println();
        System.out.println("Expected final result: PASS");
        System.out.println("Actual final result: " + (passed ? "PASS" : "FAIL"));

        if (!passed) {
            throw new IllegalStateException(
                    "Full lifecycle simulation failed."
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

    private static Level2OrderBookProfile strongLevel2(String ticker) {
        return new Level2OrderBookProfile(
                ticker,
                250_000,
                90_000,
                0.47,
                0.001,
                0.92,
                true,
                "BID_SUPPORT",
                "Strong bid support and tight spread"
        );
    }

    private static SectorMomentumProfile strongSector(String ticker) {
        return new SectorMomentumProfile(
                ticker,
                "AI_INFRASTRUCTURE",
                0.045,
                0.95,
                true,
                "VERY_STRONG_SECTOR",
                "AI infrastructure sector is strongly moving with the trade"
        );
    }
}