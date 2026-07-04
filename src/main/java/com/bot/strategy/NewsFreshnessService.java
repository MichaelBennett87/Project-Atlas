package com.bot.strategy;

import com.bot.model.NewsFreshnessProfile;

public class NewsFreshnessService {

    public NewsFreshnessProfile profile(
            long articleTimestamp
    ) {
        long ageMs =
                System.currentTimeMillis()
                        - articleTimestamp;

        if (articleTimestamp <= 0) {
            return new NewsFreshnessProfile(
                    0,
                    0.50,
                    false,
                    "UNKNOWN_FRESHNESS",
                    "Missing article timestamp"
            );
        }

        long ageSeconds =
                ageMs / 1000;

        if (ageSeconds <= 15) {
            return new NewsFreshnessProfile(
                    ageMs,
                    1.00,
                    true,
                    "ULTRA_FRESH",
                    "News arrived within 15 seconds"
            );
        }

        if (ageSeconds <= 60) {
            return new NewsFreshnessProfile(
                    ageMs,
                    0.90,
                    true,
                    "FRESH",
                    "News arrived within 1 minute"
            );
        }

        if (ageSeconds <= 300) {
            return new NewsFreshnessProfile(
                    ageMs,
                    0.80,
                    true,
                    "AGING",
                    "News is 1-5 minutes old"
            );
        }

        if (ageSeconds <= 900) {
            return new NewsFreshnessProfile(
                    ageMs,
                    0.55,
                    true,
                    "STALE",
                    "Institutional/newswire item is 5-15 minutes old"
            );
        }

        if (ageSeconds <= 1800) {
            return new NewsFreshnessProfile(
                    ageMs,
                    0.30,
                    true,
                    "VERY_STALE",
                    "Institutional/newswire item is 15-30 minutes old"
            );
        }

        return new NewsFreshnessProfile(
                ageMs,
                0.05,
                true,
                "EXPIRED",
                "News is older than 30 minutes"
        );
    }

    public boolean allowsAutoBuy(
            NewsFreshnessProfile profile
    ) {
        if (profile == null) {
            return true;
        }

        return !"EXPIRED".equals(
                profile.category
        );
    }
}