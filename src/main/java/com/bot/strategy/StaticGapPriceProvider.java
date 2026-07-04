package com.bot.strategy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class StaticGapPriceProvider implements GapPriceProvider {

    private final Map<String, Double> previousCloses =
            new HashMap<>();

    private final Map<String, Double> currentPrices =
            new HashMap<>();

    public void set(
            String ticker,
            double previousClose,
            double currentPrice
    ) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        String normalized =
                ticker.toUpperCase(Locale.ROOT);

        previousCloses.put(normalized, previousClose);
        currentPrices.put(normalized, currentPrice);
    }

    @Override
    public double getPreviousClose(String ticker) {
        if (ticker == null) {
            return 0.0;
        }

        return previousCloses.getOrDefault(
                ticker.toUpperCase(Locale.ROOT),
                0.0
        );
    }

    @Override
    public double getCurrentPrice(String ticker) {
        if (ticker == null) {
            return 0.0;
        }

        return currentPrices.getOrDefault(
                ticker.toUpperCase(Locale.ROOT),
                0.0
        );
    }
}