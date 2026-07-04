package com.bot;

import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.scanner.MomentumDiscoveryEngine;

public class MomentumDiscoveryEngineTest {
    public static void main(String[] args) {
        MomentumDiscoveryEngine engine = new MomentumDiscoveryEngine();

        MarketDataCache weakCache = new MarketDataCache();
        double weak = 10.00;
        for (int i = 0; i < 12; i++) {
            Bar b = bar("WEAK", weak, weak + 0.02, weak - 0.02, weak + 0.01, 800 + i * 5, i);
            weakCache.addBar("WEAK", b);
        }
        MomentumDiscoveryEngine.MomentumDiscoveryProfile weakProfile = engine.evaluate("WEAK", weakCache, bar("WEAK", 10.01, 10.03, 9.99, 10.02, 900, 13));
        if (weakProfile.pass) {
            throw new IllegalStateException("Weak ticker should not pass discovery: " + weakProfile.reason);
        }

        MarketDataCache strongCache = new MarketDataCache();
        double price = 1.00;
        long volume = 10_000;
        for (int i = 0; i < 18; i++) {
            double open = price;
            price *= 1.012 + (i > 10 ? 0.006 : 0.0);
            volume = (long) (volume * (i > 10 ? 1.34 : 1.08));
            strongCache.addBar("MOMO", bar("MOMO", open, price * 1.018, open * 0.995, price, volume, i));
        }
        Bar latest = bar("MOMO", price, price * 1.05, price * 0.995, price * 1.045, 600_000, 19);
        MomentumDiscoveryEngine.MomentumDiscoveryProfile strongProfile = engine.evaluate("MOMO", strongCache, latest);
        if (!strongProfile.pass) {
            throw new IllegalStateException("Strong ticker should pass discovery: " + strongProfile.reason);
        }


        MarketDataCache highPriceCache = new MarketDataCache();
        double hp = 520.00;
        long hpVol = 900;
        for (int i = 0; i < 14; i++) {
            double open = hp;
            hp *= 1.006 + (i > 8 ? 0.007 : 0.0);
            hpVol = (long) (hpVol * (i > 8 ? 1.45 : 1.05));
            highPriceCache.addBar("HPX", bar("HPX", open, hp * 1.006, open * 0.998, hp, hpVol, i));
        }
        Bar highPriceLatest = bar("HPX", hp, hp * 1.030, hp * 0.998, hp * 1.026, 9_500, 18);
        MomentumDiscoveryEngine.MomentumDiscoveryProfile highPriceProfile = engine.evaluate("HPX", highPriceCache, highPriceLatest);
        if (!highPriceProfile.pass) {
            throw new IllegalStateException("High-priced strong dollar-volume mover should pass without 25k shares: " + highPriceProfile.reason);
        }

        System.out.println("PASS MomentumDiscoveryEngineTest weak=" + weakProfile.reason + " strong=" + strongProfile.reason + " highPrice=" + highPriceProfile.reason);
    }

    private static Bar bar(String ticker, double open, double high, double low, double close, long volume, long index) {
        Bar b = new Bar();
        b.ticker = ticker;
        b.timestamp = System.currentTimeMillis() - (20 - index) * 60_000L;
        b.open = open;
        b.high = high;
        b.low = low;
        b.close = close;
        b.volume = volume;
        return b;
    }
}
