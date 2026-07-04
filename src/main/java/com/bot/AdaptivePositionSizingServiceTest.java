package com.bot;

import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.FloatProfile;
import com.bot.model.GapProfile;
import com.bot.model.MarketCapProfile;
import com.bot.model.MarketRegimeProfile;
import com.bot.model.NewsFreshnessProfile;
import com.bot.model.RelativeVolumeProfile;
import com.bot.strategy.AdaptivePositionSizingService;

public class AdaptivePositionSizingServiceTest {

    public static void main(String[] args) {

        runStrongSetupTest();
        runNormalSetupTest();
        runLowRvolDefensiveTest();
        runHighGapDefensiveTest();
        runStaleNewsDefensiveTest();
        runHostileRegimeDefensiveTest();
    }

    private static void runStrongSetupTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.92,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        lowGap(),
                        ultraFresh(),
                        hotRegime()
                );

        print(
                "STRONG SETUP SIZE TEST",
                "AGGRESSIVE_SIZE",
                profile
        );
    }

    private static void runNormalSetupTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.80,
                        lowFloat(),
                        midCap(),
                        normalRvol(),
                        lowGap(),
                        fresh(),
                        normalRegime()
                );

        print(
                "NORMAL SETUP SIZE TEST",
                "NORMAL_SIZE",
                profile
        );
    }

    private static void runLowRvolDefensiveTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.90,
                        lowFloat(),
                        midCap(),
                        lowRvol(),
                        lowGap(),
                        ultraFresh(),
                        hotRegime()
                );

        print(
                "LOW RVOL DEFENSIVE SIZE TEST",
                "DEFENSIVE_SIZE",
                profile
        );
    }

    private static void runHighGapDefensiveTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.90,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        highGap(),
                        ultraFresh(),
                        hotRegime()
                );

        print(
                "HIGH GAP DEFENSIVE SIZE TEST",
                "DEFENSIVE_SIZE",
                profile
        );
    }

    private static void runStaleNewsDefensiveTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.90,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        lowGap(),
                        stale(),
                        hotRegime()
                );

        print(
                "STALE NEWS DEFENSIVE SIZE TEST",
                "DEFENSIVE_SIZE",
                profile
        );
    }

    private static void runHostileRegimeDefensiveTest() {
        AdaptivePositionSizingService service =
                new AdaptivePositionSizingService();

        AdaptivePositionSizeProfile profile =
                service.size(
                        "SMCI",
                        10,
                        0.90,
                        lowFloat(),
                        midCap(),
                        extremeRvol(),
                        lowGap(),
                        ultraFresh(),
                        hostileRegime()
                );

        print(
                "HOSTILE REGIME DEFENSIVE SIZE TEST",
                "DEFENSIVE_SIZE",
                profile
        );
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

    private static RelativeVolumeProfile normalRvol() {
        return new RelativeVolumeProfile(
                "SMCI",
                1_000,
                1_000,
                1.0,
                0.35,
                true,
                "NORMAL_RVOL",
                "Test normal RVOL"
        );
    }

    private static RelativeVolumeProfile lowRvol() {
        return new RelativeVolumeProfile(
                "SMCI",
                300,
                1_000,
                0.30,
                0.15,
                true,
                "LOW_RVOL",
                "Test low RVOL"
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

    private static GapProfile highGap() {
        return new GapProfile(
                "SMCI",
                100.00,
                125.00,
                0.25,
                0.40,
                true,
                "HIGH_GAP",
                "Test high gap"
        );
    }

    private static NewsFreshnessProfile ultraFresh() {
        return new NewsFreshnessProfile(
                5_000,
                1.00,
                true,
                "ULTRA_FRESH",
                "Test ultra fresh"
        );
    }

    private static NewsFreshnessProfile fresh() {
        return new NewsFreshnessProfile(
                30_000,
                0.90,
                true,
                "FRESH",
                "Test fresh"
        );
    }

    private static NewsFreshnessProfile stale() {
        return new NewsFreshnessProfile(
                240_000,
                0.40,
                true,
                "STALE",
                "Test stale"
        );
    }

    private static MarketRegimeProfile hotRegime() {
        return new MarketRegimeProfile(
                10,
                1.0,
                0.04,
                0.90,
                "HOT_REGIME",
                "Test hot regime"
        );
    }

    private static MarketRegimeProfile normalRegime() {
        return new MarketRegimeProfile(
                10,
                0.80,
                0.02,
                0.65,
                "NORMAL_REGIME",
                "Test normal regime"
        );
    }

    private static MarketRegimeProfile hostileRegime() {
        return new MarketRegimeProfile(
                10,
                0.20,
                -0.03,
                0.20,
                "HOSTILE_REGIME",
                "Test hostile regime"
        );
    }

    private static void print(
            String testName,
            String expectedCategory,
            AdaptivePositionSizeProfile profile
    ) {
        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected category: " + expectedCategory);
        System.out.println("Actual category: " + profile.category);
        System.out.println(
                expectedCategory.equals(profile.category)
                        ? "PASS"
                        : "FAIL"
        );
    }
}