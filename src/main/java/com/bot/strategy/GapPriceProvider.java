package com.bot.strategy;

public interface GapPriceProvider {

    double getPreviousClose(String ticker);

    double getCurrentPrice(String ticker);
}