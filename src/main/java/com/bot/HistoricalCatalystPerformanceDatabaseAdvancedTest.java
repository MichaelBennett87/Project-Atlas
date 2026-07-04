package com.bot;

import com.bot.model.CatalystType;
import com.bot.model.PerformanceStats;
import com.bot.performance.HistoricalPerformanceDatabase;

public class HistoricalCatalystPerformanceDatabaseAdvancedTest {

    public static void main(String[] args) {
        runTickerCatalystMarketCapLearningTest();
    }

    private static void runTickerCatalystMarketCapLearningTest() {
        HistoricalPerformanceDatabase database =
                new HistoricalPerformanceDatabase();

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "SMCI_GUIDANCE_RAISE_" + i;

            database.recordSignal(
                    signalId,
                    "SMCI",
                    CatalystType.GUIDANCE_RAISE,
                    "SMCI raises guidance after strong AI server demand",
                    System.currentTimeMillis(),
                    100.00,
                    0.90,
                    "LOW_FLOAT",
                    45_000_000_000L,
                    0.60,
                    "MID_CAP",
                    0.95,
                    0.92,
                    0.95,
                    0.03,
                    8.0,
                    0.04,
                    "STRONG_SECTOR",
                    "BID_SUPPORT"
            );

            database.markPriceAfterMinutes(signalId, 5, 106.00);
            database.markPriceAfterMinutes(signalId, 15, 112.00);
            database.markPriceAfterMinutes(signalId, 30, 118.00);
            database.markPriceAfterMinutes(signalId, 60, 115.00);
            database.updatePrice("SMCI", 122.00);
            database.closeSignal(signalId, 114.00);
        }

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "AAPL_GUIDANCE_RAISE_" + i;

            database.recordSignal(
                    signalId,
                    "AAPL",
                    CatalystType.GUIDANCE_RAISE,
                    "AAPL raises guidance after strong services demand",
                    System.currentTimeMillis(),
                    100.00,
                    0.10,
                    "MEGA_FLOAT",
                    3_000_000_000_000L,
                    0.10,
                    "MEGA_CAP",
                    0.95,
                    0.72,
                    0.80,
                    0.08,
                    1.4,
                    0.01,
                    "NORMAL_SECTOR",
                    "NEUTRAL_LEVEL2"
            );

            database.markPriceAfterMinutes(signalId, 5, 100.50);
            database.markPriceAfterMinutes(signalId, 15, 101.00);
            database.markPriceAfterMinutes(signalId, 30, 101.20);
            database.markPriceAfterMinutes(signalId, 60, 100.80);
            database.updatePrice("AAPL", 101.50);
            database.closeSignal(signalId, 100.70);
        }

        PerformanceStats smciGuidanceStats =
                database.statsForTickerAndCatalyst(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE
                );

        PerformanceStats aaplGuidanceStats =
                database.statsForTickerAndCatalyst(
                        "AAPL",
                        CatalystType.GUIDANCE_RAISE
                );

        PerformanceStats smciExactSetupStats =
                database.statsForSetup(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        "LOW_FLOAT",
                        "MID_CAP"
                );

        boolean expectedTwentySignals =
                database.size() == 20;

        boolean expectedSmciOutperformsAaplAt15Minutes =
                smciGuidanceStats.averageGainAfter15Minutes >
                        aaplGuidanceStats.averageGainAfter15Minutes;

        boolean expectedSmciOutperformsAaplOnExit =
                smciGuidanceStats.averageExitGain >
                        aaplGuidanceStats.averageExitGain;

        boolean expectedSmciSetupHasTenSignals =
                smciExactSetupStats.totalSignals == 10;

        boolean expectedSmciSetupStrongFollowThrough =
                smciExactSetupStats.averageGainAfter30Minutes >= 0.15;

        boolean expectedSmciRvolHigher =
                smciGuidanceStats.averageRelativeVolume >
                        aaplGuidanceStats.averageRelativeVolume;

        boolean passed =
                expectedTwentySignals &&
                        expectedSmciOutperformsAaplAt15Minutes &&
                        expectedSmciOutperformsAaplOnExit &&
                        expectedSmciSetupHasTenSignals &&
                        expectedSmciSetupStrongFollowThrough &&
                        expectedSmciRvolHigher;

        System.out.println();
        System.out.println("=== HISTORICAL CATALYST PERFORMANCE DATABASE ADVANCED TEST ===");
        System.out.println("Database size: " + database.size());
        System.out.println();
        System.out.println("SMCI guidance stats: " + smciGuidanceStats);
        System.out.println("AAPL guidance stats: " + aaplGuidanceStats);
        System.out.println("SMCI exact setup stats: " + smciExactSetupStats);
        System.out.println();
        System.out.println("Expected 20 signals: true");
        System.out.println("Actual 20 signals: " + expectedTwentySignals);
        System.out.println("Expected SMCI outperform AAPL at 15 minutes: true");
        System.out.println("Actual SMCI outperform AAPL at 15 minutes: " + expectedSmciOutperformsAaplAt15Minutes);
        System.out.println("Expected SMCI outperform AAPL on exit: true");
        System.out.println("Actual SMCI outperform AAPL on exit: " + expectedSmciOutperformsAaplOnExit);
        System.out.println("Expected SMCI exact setup has 10 signals: true");
        System.out.println("Actual SMCI exact setup has 10 signals: " + expectedSmciSetupHasTenSignals);
        System.out.println("Expected SMCI 30-minute follow-through >= 15%: true");
        System.out.println("Actual SMCI 30-minute follow-through >= 15%: " + expectedSmciSetupStrongFollowThrough);
        System.out.println("Expected SMCI RVOL higher than AAPL: true");
        System.out.println("Actual SMCI RVOL higher than AAPL: " + expectedSmciRvolHigher);
        System.out.println();
        System.out.println("Expected final result: PASS");
        System.out.println("Actual final result: " + (passed ? "PASS" : "FAIL"));

        if (!passed) {
            throw new IllegalStateException(
                    "Historical catalyst performance database advanced test failed."
            );
        }
    }
}