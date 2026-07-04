package com.bot;

import com.bot.model.CatalystType;
import com.bot.model.PerformanceStats;
import com.bot.model.SignalPerformanceRecord;
import com.bot.performance.HistoricalPerformanceDatabase;

public class HistoricalPerformanceDatabaseTest {

    public static void main(String[] args) {

        HistoricalPerformanceDatabase database =
                new HistoricalPerformanceDatabase();

        SignalPerformanceRecord winner =
                new SignalPerformanceRecord(
                        "signal-1",
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        "SMCI raises guidance after record revenue",
                        System.currentTimeMillis(),
                        100.00,
                        0.90,
                        "LOW_FLOAT",
                        0.90,
                        0.92,
                        0.95,
                        0.03
                );

        SignalPerformanceRecord loser =
                new SignalPerformanceRecord(
                        "signal-2",
                        "AMD",
                        CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                        "AMD shows positive business momentum",
                        System.currentTimeMillis(),
                        200.00,
                        0.30,
                        "MEGA_FLOAT",
                        0.70,
                        0.60,
                        0.80,
                        0.20
                );

        database.recordSignal(winner);
        database.recordSignal(loser);

        database.updatePrice("SMCI", 102.00);
        database.updatePrice("SMCI", 104.00);
        database.updatePrice("SMCI", 103.00);
        database.closeSignal("signal-1", 103.00);

        database.updatePrice("AMD", 198.00);
        database.updatePrice("AMD", 196.00);
        database.closeSignal("signal-2", 196.00);

        PerformanceStats overall =
                database.overallStats();

        PerformanceStats guidanceStats =
                database.statsForCatalyst(
                        CatalystType.GUIDANCE_RAISE
                );

        PerformanceStats lowFloatStats =
                database.statsForFloatCategory(
                        "LOW_FLOAT"
                );

        printStats(
                "OVERALL STATS",
                overall
        );

        printStats(
                "GUIDANCE RAISE STATS",
                guidanceStats
        );

        printStats(
                "LOW FLOAT STATS",
                lowFloatStats
        );

        printResult(
                "DATABASE SIZE TEST",
                2,
                database.size()
        );

        printBooleanResult(
                "OVERALL WIN RATE TEST",
                true,
                overall.winRate == 0.50
        );

        printBooleanResult(
                "GUIDANCE WIN RATE TEST",
                true,
                guidanceStats.winRate == 1.00
        );

        printBooleanResult(
                "LOW FLOAT WIN RATE TEST",
                true,
                lowFloatStats.winRate == 1.00
        );
    }

    private static void printStats(
            String title,
            PerformanceStats stats
    ) {
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.println(stats);
    }

    private static void printResult(
            String testName,
            int expected,
            int actual
    ) {
        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(expected == actual ? "PASS" : "FAIL");
    }

    private static void printBooleanResult(
            String testName,
            boolean expected,
            boolean actual
    ) {
        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(expected == actual ? "PASS" : "FAIL");
    }
}