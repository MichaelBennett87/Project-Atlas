package com.bot.strategy;

import com.bot.model.MarketRegimeProfile;
import com.bot.model.PerformanceStats;

public class MarketRegimeService {

    private final SignalPerformanceDatabase database;

    public MarketRegimeService(
            SignalPerformanceDatabase database
    ) {
        this.database = database;
    }

    public MarketRegimeProfile currentRegime() {

        PerformanceStats stats =
                database.overallStats();

        int trades =
                stats.closedSignals;

        if (trades < 10) {
            return new MarketRegimeProfile(
                    trades,
                    stats.winRate,
                    stats.averageExitGain,
                    0.50,
                    "UNKNOWN_REGIME",
                    "Not enough completed trades"
            );
        }

        double score =
                (stats.winRate * 0.70)
                        + (Math.max(
                        -0.10,
                        Math.min(
                                0.10,
                                stats.averageExitGain
                        )
                ) * 3.0);

        if (score >= 0.80) {
            return new MarketRegimeProfile(
                    trades,
                    stats.winRate,
                    stats.averageExitGain,
                    score,
                    "HOT_REGIME",
                    "Recent signals performing exceptionally well"
            );
        }

        if (score >= 0.60) {
            return new MarketRegimeProfile(
                    trades,
                    stats.winRate,
                    stats.averageExitGain,
                    score,
                    "NORMAL_REGIME",
                    "Recent signals performing adequately"
            );
        }

        if (score >= 0.40) {
            return new MarketRegimeProfile(
                    trades,
                    stats.winRate,
                    stats.averageExitGain,
                    score,
                    "COLD_REGIME",
                    "Recent signals showing weaker follow-through"
            );
        }

        return new MarketRegimeProfile(
                trades,
                stats.winRate,
                stats.averageExitGain,
                score,
                "HOSTILE_REGIME",
                "Recent signals performing poorly"
        );
    }

    public boolean allowsAutoBuy(
            MarketRegimeProfile profile
    ) {
        if (profile == null) {
            return true;
        }

        return !"HOSTILE_REGIME".equals(
                profile.category
        );
    }
}