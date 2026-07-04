package com.bot.model;

public interface AccountService {

    double equity();

    double buyingPower();

    double dailyDrawdown();

    double lastPrice(String ticker);

    /**
     * Compatibility alias for newer unified runner code.
     * Existing services expose equity() using the original project naming style.
     */
    default double getEquity() {
        return equity();
    }

    /**
     * Compatibility alias for code that expects JavaBean-style buying power access.
     */
    default double getBuyingPower() {
        return buyingPower();
    }
}