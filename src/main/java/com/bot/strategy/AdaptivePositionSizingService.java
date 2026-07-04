package com.bot.strategy;

import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.CatalystType;
import com.bot.model.FloatProfile;
import com.bot.model.GapProfile;
import com.bot.model.MarketCapProfile;
import com.bot.model.MarketRegimeProfile;
import com.bot.model.NewsFreshnessProfile;
import com.bot.model.PerformanceStats;
import com.bot.model.RelativeVolumeProfile;

public class AdaptivePositionSizingService {

    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 100;

    private final SignalPerformanceDatabase performanceDatabase;

    public AdaptivePositionSizingService() {
        this.performanceDatabase = null;
    }

    public AdaptivePositionSizingService(
            SignalPerformanceDatabase performanceDatabase
    ) {
        this.performanceDatabase = performanceDatabase;
    }

    public AdaptivePositionSizeProfile size(
            String ticker,
            int baseQuantity,
            double autoBuyScore,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile regimeProfile
    ) {
        return sizeInternal(
                ticker,
                null,
                baseQuantity,
                autoBuyScore,
                floatProfile,
                marketCapProfile,
                relativeVolumeProfile,
                gapProfile,
                freshnessProfile,
                regimeProfile,
                false
        );
    }

    public AdaptivePositionSizeProfile sizeWithPerformance(
            String ticker,
            CatalystType catalystType,
            int baseQuantity,
            double autoBuyScore,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile regimeProfile
    ) {
        return sizeInternal(
                ticker,
                catalystType,
                baseQuantity,
                autoBuyScore,
                floatProfile,
                marketCapProfile,
                relativeVolumeProfile,
                gapProfile,
                freshnessProfile,
                regimeProfile,
                true
        );
    }

    private AdaptivePositionSizeProfile sizeInternal(
            String ticker,
            CatalystType catalystType,
            int baseQuantity,
            double autoBuyScore,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile regimeProfile,
            boolean usePerformanceHistory
    ) {
        if (baseQuantity <= 0) {
            return new AdaptivePositionSizeProfile(
                    ticker,
                    0.0,
                    baseQuantity,
                    0,
                    0.0,
                    "NO_SIZE",
                    "Base quantity is zero or negative"
            );
        }

        double confidenceScore =
                calculateConfidenceScore(
                        autoBuyScore,
                        floatProfile,
                        marketCapProfile,
                        relativeVolumeProfile,
                        gapProfile,
                        freshnessProfile,
                        regimeProfile
                );

        if (usePerformanceHistory) {
            confidenceScore =
                    applyHistoricalPerformanceAdjustment(
                            ticker,
                            catalystType,
                            confidenceScore
                    );
        }

        double multiplier =
                multiplierForConfidence(
                        confidenceScore,
                        relativeVolumeProfile,
                        gapProfile,
                        freshnessProfile,
                        regimeProfile
                );

        int finalQuantity =
                (int) Math.round(baseQuantity * multiplier);

        finalQuantity =
                Math.max(
                        MIN_QUANTITY,
                        Math.min(
                                MAX_QUANTITY,
                                finalQuantity
                        )
                );

        return new AdaptivePositionSizeProfile(
                ticker,
                confidenceScore,
                baseQuantity,
                finalQuantity,
                multiplier,
                category(confidenceScore, multiplier),
                reason(confidenceScore, multiplier)
        );
    }

    private double calculateConfidenceScore(
            double autoBuyScore,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile regimeProfile
    ) {
        double floatScore =
                floatProfile == null
                        ? 0.35
                        : floatProfile.floatScore;

        double marketCapScore =
                marketCapProfile == null
                        ? 0.35
                        : marketCapProfile.marketCapScore;

        double rvolScore =
                relativeVolumeProfile == null
                        ? 0.35
                        : relativeVolumeProfile.rvolScore;

        double gapScore =
                gapProfile == null
                        ? 0.50
                        : gapProfile.gapScore;

        double freshnessScore =
                freshnessProfile == null
                        ? 0.50
                        : freshnessProfile.freshnessScore;

        double regimeScore =
                regimeProfile == null
                        ? 0.50
                        : regimeProfile.regimeScore;

        /*
         * Volatility-first sizing:
         * Smaller float and smaller market cap now matter almost as much as the
         * raw auto-buy score because this bot is trying to capture violent news
         * moves, not slow large-cap drift.
         */
        return clamp(
                (autoBuyScore * 0.30)
                        + (floatScore * 0.20)
                        + (marketCapScore * 0.20)
                        + (rvolScore * 0.10)
                        + (gapScore * 0.06)
                        + (freshnessScore * 0.10)
                        + (regimeScore * 0.04)
        );
    }

    private double applyHistoricalPerformanceAdjustment(
            String ticker,
            CatalystType catalystType,
            double confidenceScore
    ) {
        if (performanceDatabase == null ||
                ticker == null ||
                ticker.isBlank() ||
                catalystType == null) {
            return confidenceScore;
        }

        PerformanceStats stats =
                performanceDatabase.tickerCatalystStats(
                        ticker,
                        catalystType.name()
                );

        if (stats.closedSignals < 5) {
            return confidenceScore;
        }

        double performanceAdjustment =
                stats.averageExitGain * 2.0;

        return clamp(
                confidenceScore + performanceAdjustment
        );
    }

    private double multiplierForConfidence(
            double confidenceScore,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile regimeProfile
    ) {
        if (isHostileRegime(regimeProfile)) {
            return 0.25;
        }

        if (isColdRegime(regimeProfile)) {
            return 0.50;
        }

        if (isStale(freshnessProfile)) {
            return 0.50;
        }

        if (isOverextendedGap(gapProfile)) {
            return 0.50;
        }

        if (isLowRvol(relativeVolumeProfile)) {
            return 0.50;
        }

        if (confidenceScore >= 0.92) {
            return 1.75;
        }

        if (confidenceScore >= 0.84) {
            return 1.50;
        }

        if (confidenceScore >= 0.74) {
            return 1.25;
        }

        if (confidenceScore >= 0.62) {
            return 1.00;
        }

        if (confidenceScore >= 0.48) {
            return 0.75;
        }

        return 0.50;
    }

    private boolean isHostileRegime(MarketRegimeProfile profile) {
        return profile != null &&
                "HOSTILE_REGIME".equalsIgnoreCase(profile.category);
    }

    private boolean isColdRegime(MarketRegimeProfile profile) {
        return profile != null &&
                "COLD_REGIME".equalsIgnoreCase(profile.category);
    }

    private boolean isStale(NewsFreshnessProfile profile) {
        return profile != null &&
                profile.usable &&
                (
                        "STALE".equalsIgnoreCase(profile.category) ||
                                "VERY_STALE".equalsIgnoreCase(profile.category)
                );
    }

    private boolean isOverextendedGap(GapProfile profile) {
        return profile != null &&
                profile.usable &&
                (
                        "HIGH_GAP".equalsIgnoreCase(profile.category) ||
                                "EXTREME_GAP".equalsIgnoreCase(profile.category)
                );
    }

    private boolean isLowRvol(RelativeVolumeProfile profile) {
        return profile != null &&
                profile.usable &&
                "LOW_RVOL".equalsIgnoreCase(profile.category);
    }

    private String category(
            double confidenceScore,
            double multiplier
    ) {
        if (multiplier >= 1.75) {
            return "MAX_VOLATILITY_SIZE";
        }

        if (multiplier >= 1.50) {
            return "AGGRESSIVE_SIZE";
        }

        if (multiplier >= 1.25) {
            return "INCREASED_SIZE";
        }

        if (multiplier >= 1.00) {
            return "NORMAL_SIZE";
        }

        if (multiplier >= 0.75) {
            return "REDUCED_SIZE";
        }

        return "DEFENSIVE_SIZE";
    }

    private String reason(
            double confidenceScore,
            double multiplier
    ) {
        return "Adaptive sizing selected multiplier " +
                multiplier +
                " from confidence score " +
                confidenceScore;
    }

    private double clamp(double value) {
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        value
                )
        );
    }
}