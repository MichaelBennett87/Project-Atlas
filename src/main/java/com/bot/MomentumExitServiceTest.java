package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.strategy.MomentumExitService;

public class MomentumExitServiceTest {

    public static void main(String[] args) {

        runStillRisingTest();
        runHardDropFromPeakTest();
        runMomentumFadeTest();
        runVolumeFadeTest();
    }

    private static void runStillRisingTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        MomentumExitService exitService =
                new MomentumExitService(marketData);

        String ticker = "RISE";

        for (int i = 0; i < 70; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.03),
                    2000
            );
        }

        boolean shouldExit =
                exitService.shouldExit(
                        ticker,
                        100.00,
                        102.07,
                        102.07
                );

        printResult(
                "STILL RISING TEST",
                false,
                shouldExit
        );
    }

    private static void runHardDropFromPeakTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        MomentumExitService exitService =
                new MomentumExitService(marketData);

        String ticker = "DROP";

        for (int i = 0; i < 70; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00,
                    2000
            );
        }

        boolean shouldExit =
                exitService.shouldExit(
                        ticker,
                        100.00,
                        110.00,
                        104.00
                );

        printResult(
                "HARD DROP FROM PEAK TEST",
                true,
                shouldExit
        );
    }

    private static void runMomentumFadeTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        MomentumExitService exitService =
                new MomentumExitService(marketData);

        String ticker = "FADE";

        for (int i = 0; i < 40; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.04),
                    2000
            );
        }

        for (int i = 0; i < 30; i++) {
            addBar(
                    marketData,
                    ticker,
                    101.60 - (i * 0.03),
                    2000
            );
        }

        boolean shouldExit =
                exitService.shouldExit(
                        ticker,
                        100.00,
                        101.60,
                        100.73
                );

        printResult(
                "MOMENTUM FADE TEST",
                true,
                shouldExit
        );
    }

    private static void runVolumeFadeTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        MomentumExitService exitService =
                new MomentumExitService(marketData);

        String ticker = "VOLFADE";

        for (int i = 0; i < 69; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    3000
            );
        }

        addBar(
                marketData,
                ticker,
                101.38,
                500
        );

        boolean shouldExit =
                exitService.shouldExit(
                        ticker,
                        100.00,
                        101.38,
                        101.38
                );

        printResult(
                "VOLUME FADE TEST",
                true,
                shouldExit
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

    private static void printResult(
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