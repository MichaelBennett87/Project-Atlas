package com.bot;

import com.bot.model.FloatProfile;
import com.bot.strategy.FloatAwarenessService;
import com.bot.strategy.FloatDataProvider;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FloatAwarenessServiceTest {

    public static void main(String[] args) {

        FloatAwarenessService service =
                new FloatAwarenessService(
                        new TestFloatDataProvider()
                );

        runTest(
                service,
                "MICRO FLOAT TEST",
                "MICRO",
                true,
                "MICRO_FLOAT",
                1.00
        );

        runTest(
                service,
                "LOW FLOAT TEST",
                "LOW",
                true,
                "LOW_FLOAT",
                0.90
        );

        runTest(
                service,
                "MEDIUM FLOAT TEST",
                "MEDIUM",
                true,
                "MEDIUM_FLOAT",
                0.75
        );

        runTest(
                service,
                "LARGE FLOAT TEST",
                "LARGE",
                true,
                "LARGE_FLOAT",
                0.55
        );

        runTest(
                service,
                "MEGA FLOAT TEST",
                "MEGA",
                true,
                "MEGA_FLOAT",
                0.30
        );

        runTest(
                service,
                "UNKNOWN FLOAT TEST",
                "UNKNOWN",
                false,
                "UNKNOWN_FLOAT",
                0.35
        );
    }

    private static void runTest(
            FloatAwarenessService service,
            String testName,
            String ticker,
            boolean expectedKnown,
            String expectedCategory,
            double expectedScore
    ) {
        FloatProfile profile =
                service.profile(ticker);

        boolean pass =
                profile.known == expectedKnown &&
                        expectedCategory.equals(profile.category) &&
                        Double.compare(profile.floatScore, expectedScore) == 0;

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Ticker: " + ticker);
        System.out.println("Profile: " + profile);
        System.out.println("Expected known: " + expectedKnown);
        System.out.println("Expected category: " + expectedCategory);
        System.out.println("Expected score: " + expectedScore);
        System.out.println(pass ? "PASS" : "FAIL");
    }

    private static class TestFloatDataProvider implements FloatDataProvider {

        private final Map<String, Long> floats =
                new HashMap<>();

        private TestFloatDataProvider() {
            floats.put("MICRO", 10_000_000L);
            floats.put("LOW", 50_000_000L);
            floats.put("MEDIUM", 150_000_000L);
            floats.put("LARGE", 500_000_000L);
            floats.put("MEGA", 2_000_000_000L);
        }

        @Override
        public Long getSharesFloat(String ticker) {
            if (ticker == null) {
                return null;
            }

            return floats.get(
                    ticker.toUpperCase(Locale.ROOT)
            );
        }
    }
}