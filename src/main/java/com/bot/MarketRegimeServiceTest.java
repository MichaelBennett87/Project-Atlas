package com.bot;

import com.bot.model.MarketRegimeProfile;
import com.bot.strategy.MarketRegimeService;
import com.bot.strategy.SignalPerformanceDatabase;

public class MarketRegimeServiceTest {

    public static void main(String[] args) {

        testUnknownRegime();
        testHotRegime();
        testNormalRegime();
        testColdRegime();
        testHostileRegime();
    }

    private static void testUnknownRegime() {
        SignalPerformanceDatabase db =
                new SignalPerformanceDatabase();

        MarketRegimeService service =
                new MarketRegimeService(db);

        MarketRegimeProfile profile =
                service.currentRegime();

        print(
                "UNKNOWN REGIME TEST",
                "UNKNOWN_REGIME",
                profile.category
        );
    }

    private static void testHotRegime() {
        SignalPerformanceDatabase db =
                buildDatabase(
                        10,
                        0.04
                );

        MarketRegimeService service =
                new MarketRegimeService(db);

        MarketRegimeProfile profile =
                service.currentRegime();

        print(
                "HOT REGIME TEST",
                "HOT_REGIME",
                profile.category
        );
    }

    private static void testNormalRegime() {
        SignalPerformanceDatabase db =
                buildMixedDatabase(
                        8,
                        2,
                        0.03,
                        -0.005
                );

        MarketRegimeService service =
                new MarketRegimeService(db);

        MarketRegimeProfile profile =
                service.currentRegime();

        print(
                "NORMAL REGIME TEST",
                "NORMAL_REGIME",
                profile.category
        );
    }

    private static void testColdRegime() {
        SignalPerformanceDatabase db =
                buildMixedDatabase(
                        7,
                        3,
                        0.02,
                        -0.01
                );

        MarketRegimeService service =
                new MarketRegimeService(db);

        MarketRegimeProfile profile =
                service.currentRegime();

        print(
                "COLD REGIME TEST",
                "COLD_REGIME",
                profile.category
        );
    }

    private static void testHostileRegime() {
        SignalPerformanceDatabase db =
                buildMixedDatabase(
                        2,
                        8,
                        0.01,
                        -0.04
                );

        MarketRegimeService service =
                new MarketRegimeService(db);

        MarketRegimeProfile profile =
                service.currentRegime();

        print(
                "HOSTILE REGIME TEST",
                "HOSTILE_REGIME",
                profile.category
        );
    }

    private static SignalPerformanceDatabase buildDatabase(
            int count,
            double gain
    ) {
        SignalPerformanceDatabase db =
                new SignalPerformanceDatabase();

        for (int i = 0; i < count; i++) {
            String id =
                    "signal-" + i;

            db.recordSignal(
                    id,
                    "SMCI",
                    "GUIDANCE_RAISE",
                    "LOW_FLOAT",
                    100.0
            );

            db.closeSignal(
                    id,
                    100.0 * (1 + gain),
                    gain,
                    gain,
                    0
            );
        }

        return db;
    }

    private static SignalPerformanceDatabase buildMixedDatabase(
            int wins,
            int losses,
            double winGain,
            double lossGain
    ) {
        SignalPerformanceDatabase db =
                new SignalPerformanceDatabase();

        int index = 0;

        for (int i = 0; i < wins; i++) {
            String id =
                    "signal-" + index++;

            db.recordSignal(
                    id,
                    "SMCI",
                    "GUIDANCE_RAISE",
                    "LOW_FLOAT",
                    100.0
            );

            db.closeSignal(
                    id,
                    100.0 * (1 + winGain),
                    winGain,
                    winGain,
                    0
            );
        }

        for (int i = 0; i < losses; i++) {
            String id =
                    "signal-" + index++;

            db.recordSignal(
                    id,
                    "SMCI",
                    "GUIDANCE_RAISE",
                    "LOW_FLOAT",
                    100.0
            );

            db.closeSignal(
                    id,
                    100.0 * (1 + lossGain),
                    lossGain,
                    lossGain,
                    lossGain
            );
        }

        return db;
    }

    private static void print(
            String name,
            String expected,
            String actual
    ) {
        System.out.println();
        System.out.println("=== " + name + " ===");
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println(
                expected.equals(actual)
                        ? "PASS"
                        : "FAIL"
        );
    }
}