package com.bot;

import com.bot.model.MarketDataCache;
import com.bot.strategy.MarketConfirmationFilter;

public class MarketConfirmationFilterTest {

    public static void main(String[] args) {

        MarketDataCache marketData =
                new MarketDataCache();

        MarketConfirmationFilter confirmation =
                new MarketConfirmationFilter(marketData);

        String ticker = "TEST";

        System.out.println("=== CLOSED MARKET / FLAT PRICE TEST ===");

        for (int i = 0; i < 70; i++) {
            marketData.addBar(
                    ticker,
                    100.00,
                    100.00,
                    100.00,
                    100.00,
                    1000
            );
        }

        boolean flatConfirmed =
                confirmation.confirm(ticker);

        System.out.println("Flat confirmed: " + flatConfirmed);
        System.out.println();

        System.out.println("=== AFTER HOURS PROXY MOMENTUM TEST ===");

        marketData =
                new MarketDataCache();

        confirmation =
                new MarketConfirmationFilter(marketData);

        for (int i = 0; i < 70; i++) {
            double price =
                    100.00 + (i * 0.02);

            marketData.addBar(
                    ticker,
                    price,
                    price,
                    price,
                    price,
                    1000 + (i * 50)
            );
        }

        boolean momentumConfirmed =
                confirmation.confirm(ticker);

        System.out.println("Momentum confirmed: " + momentumConfirmed);
    }
}