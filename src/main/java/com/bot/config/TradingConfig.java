package com.bot.config;

public class TradingConfig {

    public boolean tradingEnabled() {
        String value = System.getenv("TRADING_ENABLED");
        return value != null && value.equalsIgnoreCase("true");
    }

    public boolean killSwitchActive() {
        String value = System.getenv("BOT_KILL_SWITCH");
        return value != null && value.equalsIgnoreCase("true");
    }

    public boolean shortStockEnabled() {
        String value = System.getenv("SHORT_STOCK_ENABLED");
        return value != null && value.equalsIgnoreCase("true");
    }
}