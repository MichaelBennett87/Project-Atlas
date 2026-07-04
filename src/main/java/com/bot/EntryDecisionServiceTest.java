package com.bot;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.EntryDecisionService;

public class EntryDecisionServiceTest {

    public static void main(String[] args) {

        EntryDecisionService service =
                new EntryDecisionService();

        runStrongKnownFloatImmediateEntryTest(service);
        runStrongUnknownFloatPendingTest(service);
        runPositiveBusinessMomentumPendingTest(service);
        runBadOpportunityRejectTest(service);
    }

    private static void runStrongKnownFloatImmediateEntryTest(
            EntryDecisionService service
    ) {
        RankedOpportunity ranked =
                rankedOpportunity(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        0.90,
                        0.95,
                        0.03,
                        0.92,
                        true,
                        0.90
                );

        printResult(
                "STRONG KNOWN FLOAT IMMEDIATE ENTRY TEST",
                EntryDecision.IMMEDIATE_ENTRY,
                service.decide(ranked)
        );
    }

    private static void runStrongUnknownFloatPendingTest(
            EntryDecisionService service
    ) {
        RankedOpportunity ranked =
                rankedOpportunity(
                        "UNKNOWNX",
                        CatalystType.GUIDANCE_RAISE,
                        0.90,
                        0.95,
                        0.03,
                        0.92,
                        true,
                        0.90
                );

        printResult(
                "STRONG UNKNOWN FLOAT PENDING TEST",
                EntryDecision.PENDING_CONFIRMATION,
                service.decide(ranked)
        );
    }

    private static void runPositiveBusinessMomentumPendingTest(
            EntryDecisionService service
    ) {
        RankedOpportunity ranked =
                rankedOpportunity(
                        "AMD",
                        CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                        0.55,
                        0.91,
                        0.05,
                        0.86,
                        true,
                        0.70
                );

        printResult(
                "POSITIVE BUSINESS MOMENTUM PENDING TEST",
                EntryDecision.PENDING_CONFIRMATION,
                service.decide(ranked)
        );
    }

    private static void runBadOpportunityRejectTest(
            EntryDecisionService service
    ) {
        RankedOpportunity ranked =
                rankedOpportunity(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        0.90,
                        0.20,
                        0.70,
                        -0.50,
                        false,
                        0.90
                );

        printResult(
                "BAD OPPORTUNITY REJECT TEST",
                EntryDecision.REJECT,
                service.decide(ranked)
        );
    }

    private static RankedOpportunity rankedOpportunity(
            String ticker,
            CatalystType catalystType,
            double catalystWeight,
            double positive,
            double negative,
            double net,
            boolean qualityPassed,
            double marketQualityScore
    ) {
        NewsEvent news =
                new NewsEvent(
                        "test-" + ticker,
                        ticker,
                        ticker + " raises guidance after record revenue and strong demand",
                        "The company reported record revenue, strong demand, and raised full-year guidance.",
                        System.currentTimeMillis()
                );

        SentimentScore sentiment =
                new SentimentScore(
                        positive,
                        negative,
                        Math.max(
                                0.0,
                                1.0 - positive - negative
                        )
                );

        CatalystResult catalyst =
                new CatalystResult(
                        catalystType,
                        catalystWeight,
                        "Test catalyst"
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        net,
                        qualityPassed,
                        qualityPassed ? "Test passed" : "Test rejected"
                );

        MarketQuality marketQuality =
                new MarketQuality(
                        ticker,
                        100.00,
                        99.95,
                        100.05,
                        0.001,
                        true,
                        marketQualityScore
                );

        return new RankedOpportunity(
                opportunity,
                marketQuality,
                0.90,
                "Test ranked opportunity"
        );
    }

    private static void printResult(
            String testName,
            EntryDecision expected,
            EntryDecision actual
    ) {
        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(expected == actual ? "PASS" : "FAIL");
    }
}