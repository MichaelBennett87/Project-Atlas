package com.bot;

import com.bot.model.NewsFreshnessProfile;
import com.bot.strategy.NewsFreshnessService;

public class NewsFreshnessServiceTest {

    public static void main(String[] args) {

        NewsFreshnessService service =
                new NewsFreshnessService();

        run(
                service,
                "ULTRA FRESH TEST",
                5,
                "ULTRA_FRESH"
        );

        run(
                service,
                "FRESH TEST",
                30,
                "FRESH"
        );

        run(
                service,
                "AGING TEST",
                120,
                "AGING"
        );

        run(
                service,
                "STALE TEST",
                240,
                "STALE"
        );

        run(
                service,
                "VERY STALE TEST",
                600,
                "VERY_STALE"
        );
    }

    private static void run(
            NewsFreshnessService service,
            String name,
            long ageSeconds,
            String expectedCategory
    ) {
        long timestamp =
                System.currentTimeMillis()
                        - (ageSeconds * 1000);

        NewsFreshnessProfile profile =
                service.profile(timestamp);

        System.out.println();
        System.out.println("=== " + name + " ===");
        System.out.println("Profile: " + profile);
        System.out.println("Expected: " + expectedCategory);
        System.out.println("Actual: " + profile.category);
        System.out.println(
                expectedCategory.equals(profile.category)
                        ? "PASS"
                        : "FAIL"
        );
    }
}