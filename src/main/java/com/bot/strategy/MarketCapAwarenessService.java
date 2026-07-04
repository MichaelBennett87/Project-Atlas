package com.bot.strategy;

import com.bot.model.MarketCapProfile;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MarketCapAwarenessService {

    private static final long CACHE_TTL_MS =
            6L * 60L * 60L * 1000L;

    private final MarketCapDataProvider marketCapDataProvider;
    private final Map<String, CachedMarketCap> cache =
            new HashMap<>();

    public MarketCapAwarenessService() {
        this.marketCapDataProvider =
                new ManualMarketCapDataProvider();
    }

    public MarketCapAwarenessService(
            MarketCapDataProvider marketCapDataProvider
    ) {
        this.marketCapDataProvider = marketCapDataProvider;
    }

    public synchronized MarketCapProfile profile(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return unknown("");
        }

        String normalized =
                ticker.toUpperCase(Locale.ROOT);

        Long marketCap =
                getCachedMarketCap(normalized);

        if (marketCap == null || marketCap <= 0) {
            return unknown(normalized);
        }

        return classify(
                normalized,
                marketCap
        );
    }

    private Long getCachedMarketCap(String ticker) {
        CachedMarketCap cached =
                cache.get(ticker);

        long now =
                System.currentTimeMillis();

        if (cached != null &&
                now - cached.cachedAt <= CACHE_TTL_MS) {
            return cached.marketCap;
        }

        Long marketCap =
                marketCapDataProvider.getMarketCap(ticker);

        if (marketCap != null && marketCap > 0) {
            cache.put(
                    ticker,
                    new CachedMarketCap(
                            marketCap,
                            now
                    )
            );

            return marketCap;
        }

        return null;
    }

    private MarketCapProfile classify(
            String ticker,
            long marketCap
    ) {
        if (marketCap <= 300_000_000L) {
            return new MarketCapProfile(
                    ticker,
                    marketCap,
                    1.00,
                    true,
                    "MICRO_CAP",
                    "Micro cap; highest volatility priority for fresh, strong news"
            );
        }

        if (marketCap <= 2_000_000_000L) {
            return new MarketCapProfile(
                    ticker,
                    marketCap,
                    0.95,
                    true,
                    "SMALL_CAP",
                    "Small cap; high volatility priority when catalysts are strong"
            );
        }

        if (marketCap <= 10_000_000_000L) {
            return new MarketCapProfile(
                    ticker,
                    marketCap,
                    0.80,
                    true,
                    "SMALL_MID_CAP",
                    "Small/mid cap; suitable for news-momentum entries"
            );
        }

        if (marketCap <= 50_000_000_000L) {
            return new MarketCapProfile(
                    ticker,
                    marketCap,
                    0.55,
                    true,
                    "MID_CAP",
                    "Mid cap; requires stronger news and cleaner confirmation"
            );
        }

        if (marketCap <= 250_000_000_000L) {
            return new MarketCapProfile(
                    ticker,
                    marketCap,
                    0.28,
                    true,
                    "LARGE_CAP",
                    "Large cap; lower volatility priority and requires exceptional news"
            );
        }

        return new MarketCapProfile(
                ticker,
                marketCap,
                0.12,
                true,
                "MEGA_CAP",
                "Mega cap; lowest volatility priority for single-news momentum"
        );
    }

    private MarketCapProfile unknown(String ticker) {
        return new MarketCapProfile(
                ticker,
                0L,
                0.62,
                false,
                "UNKNOWN_MARKET_CAP",
                "Market cap unavailable; do not block fresh momentum, but require catalyst/quote confirmation"
        );
    }

    private static class CachedMarketCap {

        private final long marketCap;
        private final long cachedAt;

        private CachedMarketCap(
                long marketCap,
                long cachedAt
        ) {
            this.marketCap = marketCap;
            this.cachedAt = cachedAt;
        }
    }
}