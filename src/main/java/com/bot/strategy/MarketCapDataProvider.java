package com.bot.strategy;

public interface MarketCapDataProvider {

    Long getMarketCap(String ticker);
}