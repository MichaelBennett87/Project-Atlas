package com.bot.strategy;

import com.bot.model.FloatProfile;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FloatAwarenessService {

    private static final long CACHE_TTL_MS =
            6L * 60L * 60L * 1000L;

    private final FloatDataProvider floatDataProvider;
    private final Map<String, CachedFloat> cache = new HashMap<>();

    public FloatAwarenessService() {
        this.floatDataProvider =
                new CompositeFloatDataProvider();
    }

    public FloatAwarenessService(FloatDataProvider floatDataProvider) {
        this.floatDataProvider = floatDataProvider;
    }

    public synchronized FloatProfile profile(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return unknown("");
        }

        String normalized =
                ticker.toUpperCase(Locale.ROOT);

        Long sharesFloat =
                getCachedFloat(normalized);

        if (sharesFloat == null || sharesFloat <= 0) {
            return unknown(normalized);
        }

        return classify(
                normalized,
                sharesFloat
        );
    }

    public boolean autoBuyEligible(FloatProfile profile) {
        if (profile == null || !profile.known) {
            return false;
        }

        return profile.floatScore >= 0.55;
    }

    private Long getCachedFloat(String ticker) {
        CachedFloat cached =
                cache.get(ticker);

        long now =
                System.currentTimeMillis();

        if (cached != null &&
                now - cached.cachedAt <= CACHE_TTL_MS) {
            return cached.sharesFloat;
        }

        Long sharesFloat =
                floatDataProvider.getSharesFloat(ticker);

        if (sharesFloat != null && sharesFloat > 0) {
            cache.put(
                    ticker,
                    new CachedFloat(
                            sharesFloat,
                            now
                    )
            );

            return sharesFloat;
        }

        return null;
    }

    private FloatProfile classify(
            String ticker,
            long sharesFloat
    ) {
        if (sharesFloat <= 20_000_000L) {
            return new FloatProfile(
                    ticker,
                    sharesFloat,
                    1.00,
                    true,
                    "MICRO_FLOAT",
                    "Extremely low float; can move violently on news"
            );
        }

        if (sharesFloat <= 75_000_000L) {
            return new FloatProfile(
                    ticker,
                    sharesFloat,
                    0.90,
                    true,
                    "LOW_FLOAT",
                    "Low float; strong news can produce large momentum"
            );
        }

        if (sharesFloat <= 250_000_000L) {
            return new FloatProfile(
                    ticker,
                    sharesFloat,
                    0.75,
                    true,
                    "MEDIUM_FLOAT",
                    "Medium float; suitable for high-quality catalysts"
            );
        }

        if (sharesFloat <= 750_000_000L) {
            return new FloatProfile(
                    ticker,
                    sharesFloat,
                    0.55,
                    true,
                    "LARGE_FLOAT",
                    "Large float; requires stronger catalyst and volume"
            );
        }

        return new FloatProfile(
                ticker,
                sharesFloat,
                0.30,
                true,
                "MEGA_FLOAT",
                "Mega float; harder to move aggressively from single news item"
        );
    }

    private FloatProfile unknown(String ticker) {
        return new FloatProfile(
                ticker,
                0L,
                0.35,
                false,
                "UNKNOWN_FLOAT",
                "Float unavailable; require confirmation before entry"
        );
    }

    private static class CachedFloat {

        private final long sharesFloat;
        private final long cachedAt;

        private CachedFloat(
                long sharesFloat,
                long cachedAt
        ) {
            this.sharesFloat = sharesFloat;
            this.cachedAt = cachedAt;
        }
    }
}