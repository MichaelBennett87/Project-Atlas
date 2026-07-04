package com.bot.strategy;

import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.RelativeVolumeProfile;

import java.util.List;

public class RelativeVolumeService {

    private static final int CURRENT_LOOKBACK_BARS = 5;
    private static final int AVERAGE_LOOKBACK_BARS = 40;

    private final MarketDataCache marketData;

    public RelativeVolumeService(MarketDataCache marketData) {
        this.marketData = marketData;
    }

    public RelativeVolumeProfile profile(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return unusable("", "Missing ticker");
        }

        int requiredBars =
                CURRENT_LOOKBACK_BARS + AVERAGE_LOOKBACK_BARS;

        List<Bar> bars =
                marketData.recentBars(
                        ticker,
                        requiredBars
                );

        if (bars == null || bars.size() < 10) {
            return unusable(
                    ticker,
                    "Not enough volume history"
            );
        }

        if (bars.size() <= CURRENT_LOOKBACK_BARS) {
            return unusable(
                    ticker,
                    "Not enough baseline volume history"
            );
        }

        int currentStart =
                bars.size() - CURRENT_LOOKBACK_BARS;

        int baselineStart =
                Math.max(
                        0,
                        currentStart - AVERAGE_LOOKBACK_BARS
                );

        int baselineEnd =
                currentStart;

        double currentVolume =
                averageVolume(
                        bars,
                        currentStart,
                        bars.size()
                );

        double averageVolume =
                averageVolume(
                        bars,
                        baselineStart,
                        baselineEnd
                );

        if (averageVolume <= 0) {
            return unusable(
                    ticker,
                    "Average baseline volume is zero"
            );
        }

        double relativeVolume =
                currentVolume / averageVolume;

        return classify(
                ticker,
                currentVolume,
                averageVolume,
                relativeVolume
        );
    }

    private double averageVolume(
            List<Bar> bars,
            int startInclusive,
            int endExclusive
    ) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }

        int start =
                Math.max(
                        0,
                        startInclusive
                );

        int end =
                Math.min(
                        bars.size(),
                        endExclusive
                );

        if (start >= end) {
            return 0.0;
        }

        double total =
                0.0;

        for (int i = start; i < end; i++) {
            total += bars.get(i).volume;
        }

        return total / (end - start);
    }

    private RelativeVolumeProfile classify(
            String ticker,
            double currentVolume,
            double averageVolume,
            double relativeVolume
    ) {
        if (relativeVolume >= 8.0) {
            return new RelativeVolumeProfile(
                    ticker,
                    currentVolume,
                    averageVolume,
                    relativeVolume,
                    1.00,
                    true,
                    "EXTREME_RVOL",
                    "Extreme relative volume; very strong market participation"
            );
        }

        if (relativeVolume >= 4.0) {
            return new RelativeVolumeProfile(
                    ticker,
                    currentVolume,
                    averageVolume,
                    relativeVolume,
                    0.90,
                    true,
                    "VERY_HIGH_RVOL",
                    "Very high relative volume; strong confirmation"
            );
        }

        if (relativeVolume >= 2.0) {
            return new RelativeVolumeProfile(
                    ticker,
                    currentVolume,
                    averageVolume,
                    relativeVolume,
                    0.75,
                    true,
                    "HIGH_RVOL",
                    "High relative volume; useful confirmation"
            );
        }

        if (relativeVolume >= 1.2) {
            return new RelativeVolumeProfile(
                    ticker,
                    currentVolume,
                    averageVolume,
                    relativeVolume,
                    0.55,
                    true,
                    "MODERATE_RVOL",
                    "Moderate relative volume"
            );
        }

        if (relativeVolume >= 0.8) {
            return new RelativeVolumeProfile(
                    ticker,
                    currentVolume,
                    averageVolume,
                    relativeVolume,
                    0.35,
                    true,
                    "NORMAL_RVOL",
                    "Normal relative volume"
            );
        }

        return new RelativeVolumeProfile(
                ticker,
                currentVolume,
                averageVolume,
                relativeVolume,
                0.15,
                true,
                "LOW_RVOL",
                "Low relative volume; weak participation"
        );
    }

    private RelativeVolumeProfile unusable(
            String ticker,
            String reason
    ) {
        return new RelativeVolumeProfile(
                ticker,
                0.0,
                0.0,
                0.0,
                0.35,
                false,
                "UNKNOWN_RVOL",
                reason
        );
    }
}