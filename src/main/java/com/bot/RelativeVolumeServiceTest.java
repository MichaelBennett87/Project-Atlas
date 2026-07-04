package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.model.RelativeVolumeProfile;
import com.bot.strategy.RelativeVolumeService;

public class RelativeVolumeServiceTest {

    public static void main(String[] args) {

        runExtremeRvolTest();
        runHighRvolTest();
        runNormalRvolTest();
        runLowRvolTest();
        runNotEnoughBarsTest();
    }

    private static void runExtremeRvolTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        String ticker =
                "EXTREME";

        addVolumeSeries(
                marketData,
                ticker,
                40,
                1_000,
                5,
                100_000
        );

        RelativeVolumeService service =
                new RelativeVolumeService(
                        marketData
                );

        RelativeVolumeProfile profile =
                service.profile(ticker);

        printResult(
                "EXTREME RVOL TEST",
                "EXTREME_RVOL",
                profile
        );
    }

    private static void runHighRvolTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        String ticker =
                "HIGH";

        addVolumeSeries(
                marketData,
                ticker,
                40,
                1_000,
                5,
                3_000
        );

        RelativeVolumeService service =
                new RelativeVolumeService(
                        marketData
                );

        RelativeVolumeProfile profile =
                service.profile(ticker);

        printResult(
                "HIGH RVOL TEST",
                "HIGH_RVOL",
                profile
        );
    }

    private static void runNormalRvolTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        String ticker =
                "NORMAL";

        addVolumeSeries(
                marketData,
                ticker,
                40,
                1_000,
                5,
                1_000
        );

        RelativeVolumeService service =
                new RelativeVolumeService(
                        marketData
                );

        RelativeVolumeProfile profile =
                service.profile(ticker);

        printResult(
                "NORMAL RVOL TEST",
                "NORMAL_RVOL",
                profile
        );
    }

    private static void runLowRvolTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        String ticker =
                "LOW";

        addVolumeSeries(
                marketData,
                ticker,
                40,
                1_000,
                5,
                300
        );

        RelativeVolumeService service =
                new RelativeVolumeService(
                        marketData
                );

        RelativeVolumeProfile profile =
                service.profile(ticker);

        printResult(
                "LOW RVOL TEST",
                "LOW_RVOL",
                profile
        );
    }

    private static void runNotEnoughBarsTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        String ticker =
                "THIN";

        addVolumeSeries(
                marketData,
                ticker,
                3,
                1_000,
                0,
                0
        );

        RelativeVolumeService service =
                new RelativeVolumeService(
                        marketData
                );

        RelativeVolumeProfile profile =
                service.profile(ticker);

        printResult(
                "NOT ENOUGH BARS TEST",
                "UNKNOWN_RVOL",
                profile
        );
    }

    private static void addVolumeSeries(
            MarketDataCache marketData,
            String ticker,
            int baselineBars,
            long baselineVolume,
            int currentBars,
            long currentVolume
    ) {
        for (int i = 0; i < baselineBars; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00,
                    baselineVolume
            );
        }

        for (int i = 0; i < currentBars; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00,
                    currentVolume
            );
        }
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
            String expectedCategory,
            RelativeVolumeProfile profile
    ) {
        boolean pass =
                expectedCategory.equals(profile.category);

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("=== " + testName + " ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected category: " + expectedCategory);
        System.out.println("Actual category: " + profile.category);
        System.out.println(pass ? "PASS" : "FAIL");
    }
}