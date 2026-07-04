package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.strategy.MarketConfirmationFilter;

public class MarketConfirmationV3Test {

    public static void main(String[] args) {

        runFlatPriceTest();
        runGradualUptrendNoVolumeTest();
        runGradualUptrendStrongVolumeTest();
        runGradualUptrendWeakVolumeTest();
        runDowntrendTest();
        runSpikeThenFadeTest();
        runNotEnoughBarsTest();
    }

    private static void runFlatPriceTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "FLAT";

        for (int i = 0; i < 70; i++) {
            addBar(marketData, ticker, 100.00, 1000);
        }

        printResult(
                "FLAT PRICE TEST",
                false,
                confirmation.confirm(ticker)
        );
    }

    private static void runGradualUptrendNoVolumeTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "UPTREND_NO_VOLUME";

        for (int i = 0; i < 70; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    0
            );
        }

        printResult(
                "GRADUAL UPTREND NO VOLUME FALLBACK TEST",
                true,
                confirmation.confirm(ticker)
        );
    }

    private static void runGradualUptrendStrongVolumeTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "UPTREND_STRONG_VOLUME";

        for (int i = 0; i < 69; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    1000
            );
        }

        addBar(
                marketData,
                ticker,
                100.00 + (69 * 0.02),
                2000
        );

        printResult(
                "GRADUAL UPTREND STRONG VOLUME TEST",
                true,
                confirmation.confirm(ticker)
        );
    }

    private static void runGradualUptrendWeakVolumeTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "UPTREND_WEAK_VOLUME";

        for (int i = 0; i < 69; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    1000
            );
        }

        addBar(
                marketData,
                ticker,
                100.00 + (69 * 0.02),
                900
        );

        printResult(
                "GRADUAL UPTREND WEAK VOLUME TEST",
                false,
                confirmation.confirm(ticker)
        );
    }

    private static void runDowntrendTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "DOWNTREND";

        for (int i = 0; i < 70; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 - (i * 0.02),
                    2000
            );
        }

        printResult(
                "DOWNTREND TEST",
                false,
                confirmation.confirm(ticker)
        );
    }

    private static void runSpikeThenFadeTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "FADE";

        for (int i = 0; i < 30; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.05),
                    2000
            );
        }

        for (int i = 0; i < 40; i++) {
            addBar(
                    marketData,
                    ticker,
                    101.50 - (i * 0.03),
                    2000
            );
        }

        printResult(
                "SPIKE THEN FADE TEST",
                false,
                confirmation.confirm(ticker)
        );
    }

    private static void runNotEnoughBarsTest() {
        MarketDataCache marketData = new MarketDataCache();
        MarketConfirmationFilter confirmation = new MarketConfirmationFilter(marketData);

        String ticker = "THIN";

        for (int i = 0; i < 10; i++) {
            addBar(
                    marketData,
                    ticker,
                    100.00 + (i * 0.02),
                    2000
            );
        }

        printResult(
                "NOT ENOUGH BARS TEST",
                false,
                confirmation.confirm(ticker)
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