package com.bot;

import com.bot.master.MomentumIgnitionProfile;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.MarketDataCache;
import com.bot.model.TradeDirection;

public class MomentumIgnitionProfileTest {
    public static void main(String[] args) {
        MarketDataCache weak = new MarketDataCache();
        for (int i = 0; i < 12; i++) {
            weak.addBar("WEAK", 10.00, 10.05, 9.98, 10.01, 1000);
        }
        StrategySignal weakSignal = StrategySignal.buy("TEST", "WEAK", TradeDirection.LONG_STOCK, 0.9, 0.05, 1, "test");
        MomentumIgnitionProfile weakProfile = MomentumIgnitionProfile.from(new StrategyContext("WEAK", weak, null, null, 10.01, 100000), weakSignal);
        if (weakProfile.passesHardGate()) {
            throw new IllegalStateException("Weak/non-parabolic ticker should not pass momentum ignition gate: " + weakProfile.getReason());
        }

        MarketDataCache strong = new MarketDataCache();
        double price = 1.00;
        for (int i = 0; i < 10; i++) {
            strong.addBar("MOMO", price, price * 1.005, price * 0.995, price * 1.002, 3000);
            price *= 1.002;
        }
        strong.addBar("MOMO", price, price * 1.035, price * 0.995, price * 1.030, 45000);
        price *= 1.030;
        strong.addBar("MOMO", price, price * 1.055, price * 0.998, price * 1.050, 85000);
        StrategySignal strongSignal = StrategySignal.buy("TEST", "MOMO", TradeDirection.LONG_STOCK, 0.9, 0.08, 1, "test");
        MomentumIgnitionProfile strongProfile = MomentumIgnitionProfile.from(new StrategyContext("MOMO", strong, null, null, price * 1.050, 100000), strongSignal);
        if (!strongProfile.passesHardGate()) {
            throw new IllegalStateException("Explosive ticker should pass momentum ignition gate: " + strongProfile.getReason());
        }

        System.out.println("PASS MomentumIgnitionProfileTest weak=" + weakProfile.getReason() + " strong=" + strongProfile.getReason());
    }
}
