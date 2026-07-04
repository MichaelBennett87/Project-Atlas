package com.bot.risk;

import com.bot.stream.AlpacaSymbolFilter;

import java.util.Set;

public class SymbolFilter {

    private static final Set<String> BLOCKED_SYMBOLS = Set.of(
            "SPY",
            "QQQ",
            "DIA",
            "IWM",
            "VXX",
            "UVXY",
            "SQQQ",
            "TQQQ",
            "BTCUSD",
            "ETHUSD",
            "DOGEUSD",
            "SOLUSD",
            "XRPUSD",
            "SHIBUSD"
    );

    public boolean allowed(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        String normalized = AlpacaSymbolFilter.normalize(ticker);

        if (BLOCKED_SYMBOLS.contains(normalized)) {
            return false;
        }

        if (!AlpacaSymbolFilter.isEligibleStockSymbol(normalized)) {
            return false;
        }

        return normalized.matches("^[A-Z][A-Z0-9]{0,5}(\\.[A-Z])?");
    }
}