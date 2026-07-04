package com.bot.intelligence;

public interface ProbabilityModel {
    ProbabilityPrediction predict(MarketFeatureSnapshot features);
}
