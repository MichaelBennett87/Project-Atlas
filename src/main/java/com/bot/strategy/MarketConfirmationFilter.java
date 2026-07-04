package com.bot.strategy;

import com.bot.model.MarketDataCache;

public class MarketConfirmationFilter {

    private static final int ONE_MINUTE_BARS = 12;
    private static final int THREE_MINUTE_BARS = 36;
    private static final int FIVE_MINUTE_BARS = 60;
    private static final int VOLUME_LOOKBACK_BARS = 20;

    private static final double MIN_ONE_MINUTE_CHANGE = 0.0005;
    private static final double MIN_THREE_MINUTE_CHANGE = 0.0010;
    private static final double MIN_FIVE_MINUTE_CHANGE = 0.0015;
    private static final double MIN_VOLUME_RATIO = 1.20;

    private final MarketDataCache marketData;

    public MarketConfirmationFilter(MarketDataCache marketData) {
        this.marketData = marketData;
    }

    public boolean confirm(String ticker) {

        double oneMinute =
                marketData.percentChangeBars(
                        ticker,
                        ONE_MINUTE_BARS
                );

        double threeMinute =
                marketData.percentChangeBars(
                        ticker,
                        THREE_MINUTE_BARS
                );

        double fiveMinute =
                marketData.percentChangeBars(
                        ticker,
                        FIVE_MINUTE_BARS
                );

        double volumeRatio =
                marketData.volumeRatioBars(
                        ticker,
                        VOLUME_LOOKBACK_BARS
                );

        boolean hasUsableVolume =
                marketData.hasUsableVolume(
                        ticker,
                        VOLUME_LOOKBACK_BARS
                );

        boolean priceMomentumConfirmed =
                oneMinute >= MIN_ONE_MINUTE_CHANGE &&
                        threeMinute >= MIN_THREE_MINUTE_CHANGE &&
                        fiveMinute >= MIN_FIVE_MINUTE_CHANGE;

        boolean volumeConfirmed =
                !hasUsableVolume ||
                        volumeRatio >= MIN_VOLUME_RATIO;

        System.out.println("CONFIRMATION CHECK:");
        System.out.println("Ticker: " + ticker);
        System.out.println("1-minute change: " + oneMinute);
        System.out.println("3-minute change: " + threeMinute);
        System.out.println("5-minute change: " + fiveMinute);
        System.out.println("Volume ratio: " + volumeRatio);
        System.out.println("Usable volume: " + hasUsableVolume);

        boolean confirmed =
                priceMomentumConfirmed &&
                        volumeConfirmed;

        if (confirmed) {
            System.out.println(
                    "MOMENTUM CONFIRMED: " +
                            ticker
            );
        }

        return confirmed;
    }
}