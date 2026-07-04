package com.bot.master;

public interface TradingStrategy {
    String name();
    StrategySignal evaluate(StrategyContext context);
}
