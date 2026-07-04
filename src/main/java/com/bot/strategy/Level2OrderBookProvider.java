package com.bot.strategy;

import com.bot.model.Level2OrderBookProfile;

public interface Level2OrderBookProvider {

    Level2OrderBookProfile profile(String ticker);
}