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
import com.bot.strategy.StaticGapPriceProvider;

public class GapEntryDecisionServiceTest {

    public static void main(String[] args) {

        runLowGapImmediateEntryTest();
        runHighGapPendingConfirmationTest();
        runExtremeGapPendingConfirmationTest();
        runUnknownGapDoesNotBlockTest();
    }

    private static void runLowGapImmediateEntryTest() {
        StaticGapPriceProvider gapProvider =
                new StaticGapPriceProvider();

        gapProvider.set(
                "SMCI",
                100.00,
                105.00
        );

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        gapProvider
                );

        EntryDecision actual =
                service.decide(
                        strongRankedOpportunity("SMCI")
                );

        printResult(
                "LOW GAP IMMEDIATE ENTRY TEST",
                EntryDecision.IMMEDIATE_ENTRY,
                actual
        );
    }

    private static void runHighGapPendingConfirmationTest() {
        StaticGapPriceProvider gapProvider =
                new StaticGapPriceProvider();

        gapProvider.set(
                "SMCI",
                100.00,
                125.00
        );

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        gapProvider
                );

        EntryDecision actual =
                service.decide(
                        strongRankedOpportunity("SMCI")
                );

        printResult(
                "HIGH GAP PENDING CONFIRMATION TEST",
                EntryDecision.PENDING_CONFIRMATION,
                actual
        );
    }

    private static void runExtremeGapPendingConfirmationTest() {
        StaticGapPriceProvider gapProvider =
                new StaticGapPriceProvider();

        gapProvider.set(
                "SMCI",
                100.00,
                145.00
        );

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        gapProvider
                );

        EntryDecision actual =
                service.decide(
                        strongRankedOpportunity("SMCI")
                );

        printResult(
                "EXTREME GAP PENDING CONFIRMATION TEST",
                EntryDecision.PENDING_CONFIRMATION,
                actual
        );
    }

    private static void runUnknownGapDoesNotBlockTest() {
        StaticGapPriceProvider gapProvider =
                new StaticGapPriceProvider();

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        gapProvider
                );

        EntryDecision actual =
                service.decide(
                        strongRankedOpportunity("SMCI")
                );

        printResult(
                "UNKNOWN GAP DOES NOT BLOCK TEST",
                EntryDecision.IMMEDIATE_ENTRY,
                actual
        );
    }

    private static RankedOpportunity strongRankedOpportunity(String ticker) {
        NewsEvent news =
                new NewsEvent(
                        "gap-entry-test-" + ticker,
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