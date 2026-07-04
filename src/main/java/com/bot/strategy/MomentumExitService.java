package com.bot.strategy;

import com.bot.model.MarketDataCache;

public class MomentumExitService {

    private static final int ONE_MINUTE_BARS = 12;
    private static final int THREE_MINUTE_BARS = 36;
    private static final int VOLUME_LOOKBACK_BARS = 20;

    private static final double MAX_DROP_FROM_PEAK = -envDouble("MOMENTUM_EXIT_MAX_DROP_FROM_PEAK_PCT", 1.80) / 100.0;
    private static final double PROFIT_LOCK_DROP_FROM_PEAK = -envDouble("MOMENTUM_EXIT_PROFIT_LOCK_DROP_FROM_PEAK_PCT", 0.85) / 100.0;
    private static final double PROFIT_LOCK_MIN_GAIN = envDouble("MOMENTUM_EXIT_PROFIT_LOCK_MIN_GAIN_PCT", 1.25) / 100.0;
    private static final double MIN_ONE_MINUTE_MOMENTUM = -envDouble("MOMENTUM_EXIT_MIN_ONE_MINUTE_MOMENTUM_PCT", 0.08) / 100.0;
    private static final double MIN_THREE_MINUTE_MOMENTUM = envDouble("MOMENTUM_EXIT_MIN_THREE_MINUTE_MOMENTUM_PCT", 0.00) / 100.0;
    private static final double MIN_VOLUME_RATIO = envDouble("MOMENTUM_EXIT_MIN_VOLUME_RATIO", 0.75);

    private final MarketDataCache marketData;

    public MomentumExitService(MarketDataCache marketData) {
        this.marketData = marketData;
    }

    public boolean shouldExit(
            String ticker,
            double entryPrice,
            double peakPrice,
            double currentPrice
    ) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        if (entryPrice <= 0 || peakPrice <= 0 || currentPrice <= 0) {
            return false;
        }

        double dropFromPeak =
                (currentPrice - peakPrice) / peakPrice;

        double oneMinuteMomentum =
                marketData.percentChangeBars(
                        ticker,
                        ONE_MINUTE_BARS
                );

        double threeMinuteMomentum =
                marketData.percentChangeBars(
                        ticker,
                        THREE_MINUTE_BARS
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

        double gainFromEntry =
                (currentPrice - entryPrice) / entryPrice;

        boolean hardStopHit =
                dropFromPeak <= MAX_DROP_FROM_PEAK;

        boolean profitLockHit =
                gainFromEntry >= PROFIT_LOCK_MIN_GAIN &&
                        dropFromPeak <= PROFIT_LOCK_DROP_FROM_PEAK;

        boolean momentumFaded =
                oneMinuteMomentum <= MIN_ONE_MINUTE_MOMENTUM &&
                        threeMinuteMomentum <= MIN_THREE_MINUTE_MOMENTUM;

        boolean volumeFaded =
                hasUsableVolume &&
                        volumeRatio > 0 &&
                        volumeRatio < MIN_VOLUME_RATIO;

        System.out.println("EXIT CHECK:");
        System.out.println("Ticker: " + ticker);
        System.out.println("Entry price: " + entryPrice);
        System.out.println("Peak price: " + peakPrice);
        System.out.println("Current price: " + currentPrice);
        System.out.println("Drop from peak: " + dropFromPeak);
        System.out.println("Gain from entry: " + gainFromEntry);
        System.out.println("1-minute momentum: " + oneMinuteMomentum);
        System.out.println("3-minute momentum: " + threeMinuteMomentum);
        System.out.println("Volume ratio: " + volumeRatio);
        System.out.println("Usable volume: " + hasUsableVolume);

        if (hardStopHit) {
            System.out.println("EXIT TRIGGERED: hard drop from peak");
            return true;
        }

        if (profitLockHit) {
            System.out.println("EXIT TRIGGERED: profit-lock trailing drop");
            return true;
        }

        if (momentumFaded) {
            System.out.println("EXIT TRIGGERED: momentum faded");
            return true;
        }

        if (volumeFaded) {
            System.out.println("EXIT TRIGGERED: volume faded");
            return true;
        }

        return false;
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}