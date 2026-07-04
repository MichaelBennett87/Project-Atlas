package com.bot.strategy;

import com.bot.model.GapProfile;

public class GapService {

    public GapProfile profile(
            String ticker,
            double previousClose,
            double currentPrice
    ) {
        if (ticker == null || ticker.isBlank()) {
            return unusable("", "Missing ticker");
        }

        if (previousClose <= 0) {
            return unusable(ticker, "Previous close unavailable");
        }

        if (currentPrice <= 0) {
            return unusable(ticker, "Current price unavailable");
        }

        double gapPercent =
                (currentPrice - previousClose) / previousClose;

        return classify(
                ticker,
                previousClose,
                currentPrice,
                gapPercent
        );
    }

    private GapProfile classify(
            String ticker,
            double previousClose,
            double currentPrice,
            double gapPercent
    ) {
        double absoluteGap =
                Math.abs(gapPercent);

        if (absoluteGap < 0.10) {
            return new GapProfile(
                    ticker,
                    previousClose,
                    currentPrice,
                    gapPercent,
                    1.00,
                    true,
                    "LOW_GAP",
                    "Gap is under 10%; acceptable for fresh momentum entries"
            );
        }

        if (absoluteGap < 0.20) {
            return new GapProfile(
                    ticker,
                    previousClose,
                    currentPrice,
                    gapPercent,
                    0.75,
                    true,
                    "MODERATE_GAP",
                    "Gap is 10%-20%; usable but requires caution"
            );
        }

        if (absoluteGap < 0.35) {
            return new GapProfile(
                    ticker,
                    previousClose,
                    currentPrice,
                    gapPercent,
                    0.40,
                    true,
                    "HIGH_GAP",
                    "Gap is 20%-35%; too extended for aggressive auto-buy"
            );
        }

        return new GapProfile(
                ticker,
                previousClose,
                currentPrice,
                gapPercent,
                0.10,
                true,
                "EXTREME_GAP",
                "Gap is over 35%; avoid aggressive chasing"
        );
    }

    private GapProfile unusable(
            String ticker,
            String reason
    ) {
        return new GapProfile(
                ticker,
                0.0,
                0.0,
                0.0,
                0.50,
                false,
                "UNKNOWN_GAP",
                reason
        );
    }
}