package com.bot;

import com.bot.model.MarketCapProfile;
import com.bot.strategy.MarketCapAwarenessService;
import com.bot.strategy.MarketCapDataProvider;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MarketCapAwarenessServiceTest {

    public static void main(String[] args) {

        MarketCapAwarenessService service =
                new MarketCapAwarenessService(
                        new TestMarketCapDataProvider()
                );

        runTest(
                service,
                "MICRO CAP TEST",
                "MICRO",
                true,
                "MICRO_CAP",
                1.00
        );

        runTest(
                service,
                "SMALL CAP TEST",
                "SMALL",
                true,
                "SMALL_CAP",
                0.90
        );

        runTest(
                service,
                "SMALL MID CAP TEST",
                "SMALLMID",
                true,
                "SMALL_MID_CAP",
                0.75
        );

        runTest(
                service,
                "MID CAP TEST",
                "MID",
                true,
                "MID_CAP",
                0.60
        );

        runTest(
                service,
                "LARGE CAP TEST",
                "LARGE",
                true,
                "LARGE_CAP",
                0.40
        );

        runTest(
                service,
                "MEGA CAP TEST",
                "MEGA",
                true,
                "MEGA_CAP",
                0.20
        );

        runTest(
                service,
                "UNKNOWN MARKET CAP TEST",
                "UNKNOWN",
                false,
                "UNKNOWN_MARKET_CAP",
                0.35
        );
    }

    private static void runTest(
            MarketCapAwarenessService service,
            String testName,
            String ticker,
            boolean expectedKnown,
            String expectedCategory,
            double expectedScore
    ) {
        MarketCapProfile profile =
                service.profile(ticker);

        boolean pass =
                profile.known == expectedKnown &&
                        expectedCategory.equals(profile.category) &&
                        Double.compare(profile.marketCapScore, expectedScore) == 0;

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Ticker: " + ticker);
        System.out.println("Profile: " + profile);
        System.out.println("Expected known: " + expectedKnown);
        System.out.println("Expected category: " + expectedCategory);
        System.out.println("Expected score: " + expectedScore);
        System.out.println(pass ? "PASS" : "FAIL");
    }

    private static class TestMarketCapDataProvider implements MarketCapDataProvider {

        private final Map<String, Long> marketCaps =
                new HashMap<>();

        private TestMarketCapDataProvider() {
            marketCaps.put("MICRO", 200_000_000L);
            marketCaps.put("SMALL", 1_000_000_000L);
            marketCaps.put("SMALLMID", 5_000_000_000L);
            marketCaps.put("MID", 25_000_000_000L);
            marketCaps.put("LARGE", 100_000_000_000L);
            marketCaps.put("MEGA", 1_000_000_000_000L);
        }

        @Override
        public Long getMarketCap(String ticker) {
            if (ticker == null) {
                return null;
            }

            return marketCaps.get(
                    ticker.toUpperCase(Locale.ROOT)
            );
        }
    }
}