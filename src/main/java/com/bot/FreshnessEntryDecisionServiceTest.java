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

public class FreshnessEntryDecisionServiceTest {

    public static void main(String[] args) {

        runTest(
                "ULTRA FRESH IMMEDIATE ENTRY TEST",
                5,
                EntryDecision.IMMEDIATE_ENTRY
        );

        runTest(
                "FRESH IMMEDIATE ENTRY TEST",
                30,
                EntryDecision.IMMEDIATE_ENTRY
        );

        runTest(
                "AGING IMMEDIATE ENTRY TEST",
                120,
                EntryDecision.IMMEDIATE_ENTRY
        );

        runTest(
                "STALE PENDING CONFIRMATION TEST",
                240,
                EntryDecision.PENDING_CONFIRMATION
        );

        runTest(
                "VERY STALE PENDING CONFIRMATION TEST",
                600,
                EntryDecision.PENDING_CONFIRMATION
        );
    }

    private static void runTest(
            String testName,
            long ageSeconds,
            EntryDecision expected
    ) {
        EntryDecisionService service =
                new EntryDecisionService();

        EntryDecision actual =
                service.decide(
                        strongRankedOpportunity(
                                "SMCI",
                                ageSeconds
                        )
                );

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Age seconds: " + ageSeconds);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(expected == actual ? "PASS" : "FAIL");
    }

    private static RankedOpportunity strongRankedOpportunity(
            String ticker,
            long ageSeconds
    ) {
        long timestamp =
                System.currentTimeMillis() -
                        (ageSeconds * 1000L);

        NewsEvent news =
                new NewsEvent(
                        "freshness-entry-test-" + ticker + "-" + ageSeconds,
                        ticker,
                        ticker + " raises guidance after record revenue and strong demand",
                        "The company raised full-year guidance after record revenue, strong demand, and accelerating growth.",
                        timestamp
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
}