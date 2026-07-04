package com.bot;

import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.MarketQuality;
import com.bot.model.NewsEvent;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelevanceDecision;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.EntryDecisionService;
import com.bot.strategy.SignalPerformanceDatabase;

public class EntryDecisionAdaptiveSizingPerformanceTest {

    public static void main(String[] args) {
        runWinningHistoryIncreasesAdaptiveSizeTest();
        runLosingHistoryReducesAdaptiveSizeTest();
    }

    private static void runWinningHistoryIncreasesAdaptiveSizeTest() {
        SignalPerformanceDatabase performanceDatabase =
                new SignalPerformanceDatabase();

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "SMCI_WINNING_HISTORY_" + i;

            performanceDatabase.recordSignal(
                    signalId,
                    "SMCI",
                    CatalystType.GUIDANCE_RAISE.name(),
                    "LOW_FLOAT",
                    100.0
            );

            performanceDatabase.closeSignal(
                    signalId,
                    120.0
            );
        }

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        performanceDatabase
                );

        int baseQuantity =
                10;

        AdaptivePositionSizeProfile profile =
                service.adaptiveSize(
                        rankedOpportunity(),
                        RelevanceDecision.PRIMARY_SUBJECT,
                        baseQuantity
                );

        boolean quantityIncreased =
                profile.finalQuantity > baseQuantity;

        boolean categoryValid =
                "INCREASED_SIZE".equals(profile.category) ||
                        "AGGRESSIVE_SIZE".equals(profile.category);

        System.out.println();
        System.out.println("=== ENTRY DECISION ADAPTIVE SIZING WINNING HISTORY TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Base quantity: " + baseQuantity);
        System.out.println("Final quantity: " + profile.finalQuantity);
        System.out.println("Expected quantity increased: true");
        System.out.println("Actual quantity increased: " + quantityIncreased);
        System.out.println("Expected category: INCREASED_SIZE or AGGRESSIVE_SIZE");
        System.out.println("Actual category: " + profile.category);
        System.out.println(quantityIncreased && categoryValid ? "PASS" : "FAIL");
    }

    private static void runLosingHistoryReducesAdaptiveSizeTest() {
        SignalPerformanceDatabase performanceDatabase =
                new SignalPerformanceDatabase();

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "SMCI_LOSING_HISTORY_" + i;

            performanceDatabase.recordSignal(
                    signalId,
                    "SMCI",
                    CatalystType.GUIDANCE_RAISE.name(),
                    "LOW_FLOAT",
                    100.0
            );

            performanceDatabase.closeSignal(
                    signalId,
                    80.0
            );
        }

        EntryDecisionService service =
                new EntryDecisionService(
                        null,
                        null,
                        null,
                        performanceDatabase
                );

        int baseQuantity =
                10;

        AdaptivePositionSizeProfile profile =
                service.adaptiveSize(
                        rankedOpportunity(),
                        RelevanceDecision.PRIMARY_SUBJECT,
                        baseQuantity
                );

        boolean quantityReduced =
                profile.finalQuantity < baseQuantity;

        boolean categoryValid =
                "REDUCED_SIZE".equals(profile.category) ||
                        "DEFENSIVE_SIZE".equals(profile.category);

        System.out.println();
        System.out.println("=== ENTRY DECISION ADAPTIVE SIZING LOSING HISTORY TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Base quantity: " + baseQuantity);
        System.out.println("Final quantity: " + profile.finalQuantity);
        System.out.println("Expected quantity reduced: true");
        System.out.println("Actual quantity reduced: " + quantityReduced);
        System.out.println("Expected category: REDUCED_SIZE or DEFENSIVE_SIZE");
        System.out.println("Actual category: " + profile.category);
        System.out.println(quantityReduced && categoryValid ? "PASS" : "FAIL");
    }

    private static RankedOpportunity rankedOpportunity() {
        NewsEvent news =
                new NewsEvent(
                        "entry-sizing-test-smci",
                        "SMCI",
                        "SMCI raises guidance after record revenue and strong demand",
                        "The company reported record revenue, strong demand, and raised full-year guidance.",
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
                        "Test catalyst"
                );

        NewsOpportunity opportunity =
                new NewsOpportunity(
                        news,
                        sentiment,
                        catalyst,
                        0.92,
                        true,
                        "High quality test opportunity"
                );

        MarketQuality marketQuality =
                new MarketQuality(
                        "SMCI",
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
                0.98,
                "High ranking test opportunity"
        );
    }
}