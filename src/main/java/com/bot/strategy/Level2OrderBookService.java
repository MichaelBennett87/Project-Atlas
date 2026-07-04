package com.bot.strategy;

import com.bot.model.Level2OrderBookProfile;

public class Level2OrderBookService {

    private final Level2OrderBookProvider provider;

    public Level2OrderBookService(Level2OrderBookProvider provider) {
        this.provider = provider;
    }

    public Level2OrderBookProfile profile(String ticker) {
        if (provider == null) {
            return unknown(ticker);
        }

        Level2OrderBookProfile provided =
                provider.profile(ticker);

        if (provided == null) {
            return unknown(ticker);
        }

        return provided;
    }

    public boolean allowsAutoBuy(Level2OrderBookProfile profile) {
        if (profile == null || !profile.usable) {
            return true;
        }

        return !"HEAVY_ASK_PRESSURE".equalsIgnoreCase(profile.category) &&
                !"WIDE_LEVEL2_SPREAD".equalsIgnoreCase(profile.category);
    }

    private Level2OrderBookProfile unknown(String ticker) {
        return new Level2OrderBookProfile(
                ticker,
                0.0,
                0.0,
                0.0,
                0.0,
                0.50,
                false,
                "UNKNOWN_LEVEL2",
                "Level 2 order book provider unavailable"
        );
    }
}