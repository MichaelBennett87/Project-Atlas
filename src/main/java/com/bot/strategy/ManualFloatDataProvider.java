package com.bot.strategy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ManualFloatDataProvider implements FloatDataProvider {

    private final Map<String, Long> knownFloats = new HashMap<>();

    public ManualFloatDataProvider() {
        knownFloats.put("AAPL", 15_300_000_000L);
        knownFloats.put("MSFT", 7_430_000_000L);
        knownFloats.put("AMZN", 10_700_000_000L);
        knownFloats.put("GOOGL", 5_800_000_000L);
        knownFloats.put("GOOG", 5_600_000_000L);
        knownFloats.put("META", 2_200_000_000L);
        knownFloats.put("TSLA", 3_200_000_000L);
        knownFloats.put("DDOG", 310_000_000L);
        knownFloats.put("LEN", 220_000_000L);
        knownFloats.put("TSM", 5_200_000_000L);
        knownFloats.put("AMD", 1_620_000_000L);
        knownFloats.put("NVDA", 24_000_000_000L);
        knownFloats.put("MU", 1_110_000_000L);
        knownFloats.put("MRVL", 865_000_000L);
        knownFloats.put("SNDK", 440_000_000L);
        knownFloats.put("SMCI", 58_000_000L);
        knownFloats.put("SOUN", 360_000_000L);
        knownFloats.put("RKLB", 480_000_000L);
        knownFloats.put("ALAB", 145_000_000L);
        knownFloats.put("CRWV", 120_000_000L);
        knownFloats.put("NBIS", 210_000_000L);
    }

    @Override
    public Long getSharesFloat(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }

        return knownFloats.get(
                ticker.toUpperCase(Locale.ROOT)
        );
    }
}