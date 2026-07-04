package com.bot.strategy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ManualMarketCapDataProvider implements MarketCapDataProvider {

    private final Map<String, Long> knownMarketCaps =
            new HashMap<>();

    public ManualMarketCapDataProvider() {

        knownMarketCaps.put("AAPL", 3_000_000_000_000L);
        knownMarketCaps.put("MSFT", 3_200_000_000_000L);
        knownMarketCaps.put("NVDA", 4_000_000_000_000L);
        knownMarketCaps.put("AMZN", 2_300_000_000_000L);
        knownMarketCaps.put("GOOGL", 2_200_000_000_000L);
        knownMarketCaps.put("GOOG", 2_200_000_000_000L);
        knownMarketCaps.put("META", 1_800_000_000_000L);
        knownMarketCaps.put("TSLA", 1_000_000_000_000L);
        knownMarketCaps.put("DDOG", 75_000_000_000L);
        knownMarketCaps.put("LEN", 25_000_000_000L);
        knownMarketCaps.put("TSM", 1_000_000_000_000L);

        knownMarketCaps.put("AMD", 260_000_000_000L);
        knownMarketCaps.put("MU", 150_000_000_000L);
        knownMarketCaps.put("MRVL", 90_000_000_000L);
        knownMarketCaps.put("SNDK", 35_000_000_000L);
        knownMarketCaps.put("SMCI", 45_000_000_000L);
        knownMarketCaps.put("SOUN", 4_000_000_000L);
        knownMarketCaps.put("RKLB", 12_000_000_000L);
        knownMarketCaps.put("ALAB", 15_000_000_000L);
        knownMarketCaps.put("CRWV", 55_000_000_000L);
        knownMarketCaps.put("NBIS", 20_000_000_000L);
    }

    @Override
    public Long getMarketCap(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }

        return knownMarketCaps.get(
                ticker.toUpperCase(Locale.ROOT)
        );
    }
}