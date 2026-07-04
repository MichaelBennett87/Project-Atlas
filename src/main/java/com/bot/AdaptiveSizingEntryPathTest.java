package com.bot;

import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.CatalystType;
import com.bot.model.FloatProfile;
import com.bot.model.GapProfile;
import com.bot.model.MarketCapProfile;
import com.bot.model.MarketRegimeProfile;
import com.bot.model.NewsFreshnessProfile;
import com.bot.model.RelativeVolumeProfile;
import com.bot.strategy.AdaptivePositionSizingService;
import com.bot.strategy.SignalPerformanceDatabase;

public class AdaptiveSizingEntryPathTest {

    public static void main(String[] args) {
        runAdaptiveSizingPositivePerformanceTest();
        runAdaptiveSizingPoorPerformanceTest();
    }

    private static void runAdaptiveSizingPositivePerformanceTest() {
        SignalPerformanceDatabase performanceDatabase =
                new SignalPerformanceDatabase();

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "SMCI_GUIDANCE_RAISE_WIN_" + i;

            performanceDatabase.recordSignal(
                    signalId,
                    "SMCI",
                    CatalystType.GUIDANCE_RAISE.name(),
                    "LOW_FLOAT",
                    100.0
            );

            performanceDatabase.closeSignal(
                    signalId,
                    104.0
            );
        }

        AdaptivePositionSizingService sizingService =
                new AdaptivePositionSizingService(performanceDatabase);

        int baseQuantity =
                10;

        AdaptivePositionSizeProfile profile =
                sizingService.sizeWithPerformance(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        baseQuantity,
                        0.92,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        lowGap(),
                        ultraFresh(),
                        hotRegime()
                );

        boolean actualQuantityIncreased =
                profile.finalQuantity > baseQuantity;

        boolean actualCategoryValid =
                "INCREASED_SIZE".equals(profile.category) ||
                        "AGGRESSIVE_SIZE".equals(profile.category);

        System.out.println();
        System.out.println("=== ADAPTIVE SIZING POSITIVE PERFORMANCE TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Base quantity: " + baseQuantity);
        System.out.println("Final quantity: " + profile.finalQuantity);
        System.out.println("Expected quantity increased: true");
        System.out.println("Actual quantity increased: " + actualQuantityIncreased);
        System.out.println("Expected category: INCREASED_SIZE or AGGRESSIVE_SIZE");
        System.out.println("Actual category: " + profile.category);

        if (actualQuantityIncreased && actualCategoryValid) {
            System.out.println("PASS");
        } else {
            System.out.println("FAIL");
        }
    }

    private static void runAdaptiveSizingPoorPerformanceTest() {
        SignalPerformanceDatabase performanceDatabase =
                new SignalPerformanceDatabase();

        for (int i = 0; i < 10; i++) {
            String signalId =
                    "SMCI_GUIDANCE_RAISE_LOSS_" + i;

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

        AdaptivePositionSizingService sizingService =
                new AdaptivePositionSizingService(performanceDatabase);

        int baseQuantity =
                10;

        AdaptivePositionSizeProfile profile =
                sizingService.sizeWithPerformance(
                        "SMCI",
                        CatalystType.GUIDANCE_RAISE,
                        baseQuantity,
                        0.92,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        lowGap(),
                        ultraFresh(),
                        hotRegime()
                );

        boolean actualQuantityReduced =
                profile.finalQuantity < baseQuantity;

        boolean actualCategoryValid =
                "REDUCED_SIZE".equals(profile.category) ||
                        "DEFENSIVE_SIZE".equals(profile.category);

        System.out.println();
        System.out.println("=== ADAPTIVE SIZING POOR PERFORMANCE TEST ===");
        System.out.println("Profile: " + profile);
        System.out.println("Base quantity: " + baseQuantity);
        System.out.println("Final quantity: " + profile.finalQuantity);
        System.out.println("Expected quantity reduced: true");
        System.out.println("Actual quantity reduced: " + actualQuantityReduced);
        System.out.println("Expected category: REDUCED_SIZE or DEFENSIVE_SIZE");
        System.out.println("Actual category: " + profile.category);

        if (actualQuantityReduced && actualCategoryValid) {
            System.out.println("PASS");
        } else {
            System.out.println("FAIL");
        }
    }

    private static FloatProfile lowFloat() {
        return new FloatProfile(
                "SMCI",
                58_000_000,
                0.90,
                true,
                "LOW_FLOAT",
                "Test low float"
        );
    }

    private static MarketCapProfile midCap() {
        return new MarketCapProfile(
                "SMCI",
                45_000_000_000L,
                0.60,
                true,
                "MID_CAP",
                "Test mid cap"
        );
    }

    private static RelativeVolumeProfile extremeRvol() {
        return new RelativeVolumeProfile(
                "SMCI",
                100_000,
                1_000,
                100.0,
                1.00,
                true,
                "EXTREME_RVOL",
                "Test extreme RVOL"
        );
    }

    private static GapProfile lowGap() {
        return new GapProfile(
                "SMCI",
                100.00,
                105.00,
                0.05,
                1.00,
                true,
                "LOW_GAP",
                "Test low gap"
        );
    }

    private static NewsFreshnessProfile ultraFresh() {
        return new NewsFreshnessProfile(
                30_000,
                1.00,
                true,
                "ULTRA_FRESH",
                "Test ultra fresh news"
        );
    }

    private static MarketRegimeProfile hotRegime() {
        return new MarketRegimeProfile(
                20,
                0.75,
                0.04,
                0.90,
                "HOT_REGIME",
                "Test hot market regime"
        );
    }
}