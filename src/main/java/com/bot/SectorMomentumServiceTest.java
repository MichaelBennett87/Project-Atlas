package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.model.SectorMomentumProfile;
import com.bot.strategy.SectorMomentumService;

public class SectorMomentumServiceTest {

    public static void main(String[] args) {
        runStrongAiSectorMomentumTest();
        runHostileBankSectorMomentumTest();
        runUnknownSectorMomentumTest();
    }

    private static void runStrongAiSectorMomentumTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        seedRisingTicker(
                marketData,
                "SMCI",
                100.00,
                106.00
        );

        seedRisingTicker(
                marketData,
                "NVDA",
                100.00,
                105.00
        );

        seedRisingTicker(
                marketData,
                "AMD",
                100.00,
                104.00
        );

        seedRisingTicker(
                marketData,
                "AVGO",
                100.00,
                103.00
        );

        SectorMomentumService service =
                new SectorMomentumService(
                        marketData
                );

        SectorMomentumProfile profile =
                service.profile(
                        "SMCI"
                );

        boolean expectedUsable =
                profile.usable;

        boolean expectedStrongCategory =
                "VERY_STRONG_SECTOR".equals(profile.category) ||
                        "STRONG_SECTOR".equals(profile.category);

        boolean expectedAutoBuyAllowed =
                service.allowsAutoBuy(profile);

        boolean passed =
                expectedUsable &&
                        expectedStrongCategory &&
                        expectedAutoBuyAllowed;

        System.out.println();
        System.out.println("=== STRONG AI SECTOR MOMENTUM TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected usable: true");
        System.out.println("Actual usable: " + expectedUsable);
        System.out.println("Expected category: VERY_STRONG_SECTOR or STRONG_SECTOR");
        System.out.println("Actual category: " + profile.category);
        System.out.println("Expected auto-buy allowed: true");
        System.out.println("Actual auto-buy allowed: " + expectedAutoBuyAllowed);
        System.out.println(passed ? "PASS" : "FAIL");

        if (!passed) {
            throw new IllegalStateException(
                    "Strong AI sector momentum test failed."
            );
        }
    }

    private static void runHostileBankSectorMomentumTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        seedFallingTicker(
                marketData,
                "JPM",
                100.00,
                97.00
        );

        seedFallingTicker(
                marketData,
                "BAC",
                100.00,
                96.00
        );

        seedFallingTicker(
                marketData,
                "WFC",
                100.00,
                97.50
        );

        seedFallingTicker(
                marketData,
                "C",
                100.00,
                98.00
        );

        SectorMomentumService service =
                new SectorMomentumService(
                        marketData
                );

        SectorMomentumProfile profile =
                service.profile(
                        "JPM"
                );

        boolean expectedUsable =
                profile.usable;

        boolean expectedHostileCategory =
                "HOSTILE_SECTOR".equals(profile.category) ||
                        "WEAK_SECTOR".equals(profile.category);

        boolean expectedAutoBuyBlocked =
                !service.allowsAutoBuy(profile);

        boolean passed =
                expectedUsable &&
                        expectedHostileCategory &&
                        expectedAutoBuyBlocked;

        System.out.println();
        System.out.println("=== HOSTILE BANK SECTOR MOMENTUM TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected usable: true");
        System.out.println("Actual usable: " + expectedUsable);
        System.out.println("Expected category: HOSTILE_SECTOR or WEAK_SECTOR");
        System.out.println("Actual category: " + profile.category);
        System.out.println("Expected auto-buy blocked: true");
        System.out.println("Actual auto-buy blocked: " + expectedAutoBuyBlocked);
        System.out.println(passed ? "PASS" : "FAIL");

        if (!passed) {
            throw new IllegalStateException(
                    "Hostile bank sector momentum test failed."
            );
        }
    }

    private static void runUnknownSectorMomentumTest() {
        MarketDataCache marketData =
                new MarketDataCache();

        SectorMomentumService service =
                new SectorMomentumService(
                        marketData
                );

        SectorMomentumProfile profile =
                service.profile(
                        "XYZ"
                );

        boolean expectedUnusable =
                !profile.usable;

        boolean expectedUnknownCategory =
                "UNKNOWN_SECTOR".equals(profile.category);

        boolean expectedAllowedBecauseUnknown =
                service.allowsAutoBuy(profile);

        boolean passed =
                expectedUnusable &&
                        expectedUnknownCategory &&
                        expectedAllowedBecauseUnknown;

        System.out.println();
        System.out.println("=== UNKNOWN SECTOR MOMENTUM TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected usable: false");
        System.out.println("Actual usable: " + profile.usable);
        System.out.println("Expected category: UNKNOWN_SECTOR");
        System.out.println("Actual category: " + profile.category);
        System.out.println("Expected auto-buy allowed because unknown: true");
        System.out.println("Actual auto-buy allowed because unknown: " + expectedAllowedBecauseUnknown);
        System.out.println(passed ? "PASS" : "FAIL");

        if (!passed) {
            throw new IllegalStateException(
                    "Unknown sector momentum test failed."
            );
        }
    }

    private static void seedRisingTicker(
            MarketDataCache marketData,
            String ticker,
            double startPrice,
            double endPrice
    ) {
        seedLinearBars(
                marketData,
                ticker,
                startPrice,
                endPrice
        );
    }

    private static void seedFallingTicker(
            MarketDataCache marketData,
            String ticker,
            double startPrice,
            double endPrice
    ) {
        seedLinearBars(
                marketData,
                ticker,
                startPrice,
                endPrice
        );
    }

    private static void seedLinearBars(
            MarketDataCache marketData,
            String ticker,
            double startPrice,
            double endPrice
    ) {
        int bars =
                6;

        for (int i = 0; i < bars; i++) {
            double progress =
                    (double) i / (bars - 1);

            double close =
                    startPrice + ((endPrice - startPrice) * progress);

            marketData.addBar(
                    ticker,
                    close,
                    close,
                    close,
                    close,
                    1_000_000L
            );
        }
    }
}