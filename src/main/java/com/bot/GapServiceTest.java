package com.bot;

import com.bot.model.GapProfile;
import com.bot.strategy.GapService;

public class GapServiceTest {

    public static void main(String[] args) {

        GapService service =
                new GapService();

        runTest(
                service,
                "LOW GAP TEST",
                "LOW",
                100.00,
                105.00,
                "LOW_GAP"
        );

        runTest(
                service,
                "MODERATE GAP TEST",
                "MODERATE",
                100.00,
                115.00,
                "MODERATE_GAP"
        );

        runTest(
                service,
                "HIGH GAP TEST",
                "HIGH",
                100.00,
                125.00,
                "HIGH_GAP"
        );

        runTest(
                service,
                "EXTREME GAP TEST",
                "EXTREME",
                100.00,
                145.00,
                "EXTREME_GAP"
        );

        runTest(
                service,
                "GAP DOWN TEST",
                "DOWN",
                100.00,
                85.00,
                "MODERATE_GAP"
        );

        runTest(
                service,
                "UNKNOWN GAP TEST",
                "UNKNOWN",
                0.00,
                100.00,
                "UNKNOWN_GAP"
        );
    }

    private static void runTest(
            GapService service,
            String testName,
            String ticker,
            double previousClose,
            double currentPrice,
            String expectedCategory
    ) {
        GapProfile profile =
                service.profile(
                        ticker,
                        previousClose,
                        currentPrice
                );

        boolean pass =
                expectedCategory.equals(profile.category);

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected category: " + expectedCategory);
        System.out.println("Actual category: " + profile.category);
        System.out.println(pass ? "PASS" : "FAIL");
    }
}